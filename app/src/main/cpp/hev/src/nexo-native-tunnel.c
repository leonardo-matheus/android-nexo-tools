#if defined(__ANDROID__) && defined(NEXO_TUNNEL)

#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/uio.h>
#include <time.h>
#include <unistd.h>

#include <lwip/pbuf.h>

#include "hev-main.h"
#include "nexo-native-tunnel.h"

#define NEXO_SNAPLEN 2048u
#define NEXO_RECORD_SIZE (16u + NEXO_SNAPLEN)
#define NEXO_CONFIG_LIMIT 65536

enum NexoPhase {
    NEXO_IDLE,
    NEXO_PREPARED,
    NEXO_STARTING,
    NEXO_RUNNING,
    NEXO_FINISHING,
};

static pthread_mutex_t lifecycle_mutex = PTHREAD_MUTEX_INITIALIZER;
static enum NexoPhase phase;
static int cancel_requested;
static int engine_ready;
static uint64_t token_counter;
static uint64_t current_token;
static atomic_uint_least64_t mirror_dropped;

/* Mirror fields and scratch buffer are used only by the single HEV worker. */
static int mirror_fd = -1;
static int mirror_enabled;
static int mirror_original_flags = -1;
static int mirror_header_written;
static unsigned char mirror_packet[NEXO_SNAPLEN];

static void
put_le16 (unsigned char *dest, uint16_t value)
{
    dest[0] = (unsigned char)value;
    dest[1] = (unsigned char)(value >> 8);
}

static void
put_le32 (unsigned char *dest, uint32_t value)
{
    dest[0] = (unsigned char)value;
    dest[1] = (unsigned char)(value >> 8);
    dest[2] = (unsigned char)(value >> 16);
    dest[3] = (unsigned char)(value >> 24);
}

/* One non-blocking writev per complete PCAP item, always <= PIPE_BUF. */
static int
mirror_write (const struct iovec *iov, int count, size_t expected)
{
    ssize_t written = writev (mirror_fd, iov, count);

    if (written == (ssize_t)expected)
        return 1;

    /* EAGAIN/EINTR wrote no bytes for an atomic pipe item; skip the item. */
    if ((written >= 0) || ((errno != EAGAIN) && (errno != EWOULDBLOCK) &&
                           (errno != EINTR)))
        mirror_fd = -1;
    return 0;
}

static int
mirror_write_header (void)
{
    unsigned char header[24] = { 0 };
    struct iovec iov = { header, sizeof (header) };

    put_le32 (header, UINT32_C (0xa1b2c3d4));
    put_le16 (header + 4, 2);
    put_le16 (header + 6, 4);
    put_le32 (header + 16, NEXO_SNAPLEN);
    put_le32 (header + 20, 101); /* LINKTYPE_RAW: IPv4/IPv6, no Ethernet. */
    mirror_header_written = mirror_write (&iov, 1, sizeof (header));
    return mirror_header_written;
}

static int
mirror_begin (int fd)
{
    struct stat metadata;
    long atomic_limit;
    int flags;

    mirror_fd = -1;
    mirror_enabled = fd >= 0;
    mirror_original_flags = -1;
    mirror_header_written = 0;
    if (!mirror_enabled)
        return 0;

    if ((fstat (fd, &metadata) != 0) || !S_ISFIFO (metadata.st_mode))
        return -1;
    atomic_limit = fpathconf (fd, _PC_PIPE_BUF);
    if (atomic_limit < (long)NEXO_RECORD_SIZE)
        return -1;
    flags = fcntl (fd, F_GETFL);
    if ((flags < 0) || ((flags & O_ACCMODE) == O_RDONLY))
        return -1;
    if (fcntl (fd, F_SETFL, flags | O_NONBLOCK) < 0)
        return -1;

    mirror_fd = fd;
    mirror_original_flags = flags;
    mirror_write_header ();
    return mirror_fd < 0 ? -1 : 0;
}

static void
mirror_end (int borrowed_fd)
{
    if ((mirror_original_flags >= 0) && (borrowed_fd >= 0))
        fcntl (borrowed_fd, F_SETFL, mirror_original_flags);
    mirror_fd = -1;
    mirror_enabled = 0;
    mirror_original_flags = -1;
}

void
nexo_tunnel_observe (const struct pbuf *packet)
{
    unsigned char header[16];
    struct iovec iov[2];
    struct timeval timestamp;
    uint16_t captured;
    unsigned int version;

    if (!mirror_enabled || !packet || !packet->tot_len)
        return;
    version = pbuf_get_at (packet, 0) >> 4;
    if ((version != 4) && (version != 6))
        return;

    if ((mirror_fd < 0) ||
        (!mirror_header_written && !mirror_write_header ()))
        goto dropped;
    captured = packet->tot_len > NEXO_SNAPLEN ? NEXO_SNAPLEN : packet->tot_len;
    if (pbuf_copy_partial (packet, mirror_packet, captured, 0) != captured)
        goto dropped;
    if (gettimeofday (&timestamp, NULL) != 0)
        goto dropped;

    put_le32 (header, (uint32_t)timestamp.tv_sec);
    put_le32 (header + 4, (uint32_t)timestamp.tv_usec);
    put_le32 (header + 8, captured);
    put_le32 (header + 12, packet->tot_len);
    iov[0].iov_base = header;
    iov[0].iov_len = sizeof (header);
    iov[1].iov_base = mirror_packet;
    iov[1].iov_len = captured;
    if (mirror_write (iov, 2, sizeof (header) + captured))
        return;

dropped:
    atomic_fetch_add_explicit (&mirror_dropped, 1, memory_order_relaxed);
}

void
nexo_tunnel_native_ready (void)
{
    pthread_mutex_lock (&lifecycle_mutex);
    engine_ready = 1;
    phase = NEXO_RUNNING;
    if (cancel_requested)
        hev_socks5_tunnel_quit ();
    pthread_mutex_unlock (&lifecycle_mutex);
}

void
nexo_tunnel_native_finished (void)
{
    pthread_mutex_lock (&lifecycle_mutex);
    engine_ready = 0;
    phase = NEXO_FINISHING;
    pthread_mutex_unlock (&lifecycle_mutex);
}

static int
is_cancelled (void)
{
    int value;
    pthread_mutex_lock (&lifecycle_mutex);
    value = cancel_requested;
    pthread_mutex_unlock (&lifecycle_mutex);
    return value;
}

JNIEXPORT jlong JNICALL
Java_br_com_nexotools_network_NativeTunnel_prepare (JNIEnv *env, jclass klass)
{
    uint64_t token;
    (void)env;
    (void)klass;
    pthread_mutex_lock (&lifecycle_mutex);
    if ((phase != NEXO_IDLE) || (token_counter == INT64_MAX)) {
        pthread_mutex_unlock (&lifecycle_mutex);
        return 0;
    }
    token = ++token_counter;
    current_token = token;
    phase = NEXO_PREPARED;
    cancel_requested = 0;
    engine_ready = 0;
    atomic_store_explicit (&mirror_dropped, 0, memory_order_relaxed);
    pthread_mutex_unlock (&lifecycle_mutex);
    return (jlong)token;
}

JNIEXPORT jint JNICALL
Java_br_com_nexotools_network_NativeTunnel_run (JNIEnv *env, jclass klass,
                                               jstring config, jint tun_fd,
                                               jint borrowed_mirror_fd,
                                               jlong token)
{
    const char *utf = NULL;
    unsigned char *config_bytes = NULL;
    jsize config_length;
    sigset_t blocked, old_mask, pending;
    struct timespec no_wait = { 0, 0 };
    int signal_mask_set = 0;
    int pipe_signal_was_pending = 0;
    int result = -1;
    int cancelled;
    (void)klass;

    pthread_mutex_lock (&lifecycle_mutex);
    if ((token <= 0) || ((uint64_t)token != current_token)) {
        pthread_mutex_unlock (&lifecycle_mutex);
        return -2;
    }
    if (phase != NEXO_PREPARED) {
        pthread_mutex_unlock (&lifecycle_mutex);
        return -3;
    }
    if (cancel_requested) {
        phase = NEXO_IDLE;
        pthread_mutex_unlock (&lifecycle_mutex);
        return -2;
    }
    phase = NEXO_STARTING;
    engine_ready = 0;
    pthread_mutex_unlock (&lifecycle_mutex);

    if (!config || (tun_fd < 0))
        goto finished;
    config_length = (*env)->GetStringUTFLength (env, config);
    if ((config_length <= 0) || (config_length > NEXO_CONFIG_LIMIT))
        goto finished;
    utf = (*env)->GetStringUTFChars (env, config, NULL);
    if (!utf)
        goto finished;
    config_bytes = malloc ((size_t)config_length + 1);
    if (config_bytes)
        memcpy (config_bytes, utf, (size_t)config_length + 1);
    (*env)->ReleaseStringUTFChars (env, config, utf);
    if (!config_bytes)
        goto finished;

    /* Protect only this worker from EPIPE; do not change process handlers. */
    sigemptyset (&blocked);
    sigaddset (&blocked, SIGPIPE);
    if (pthread_sigmask (SIG_BLOCK, &blocked, &old_mask) != 0)
        goto finished;
    signal_mask_set = 1;
    if (sigpending (&pending) == 0)
        pipe_signal_was_pending = sigismember (&pending, SIGPIPE) == 1;

    if (is_cancelled () || (mirror_begin (borrowed_mirror_fd) != 0))
        goto finished;
    if (!is_cancelled ())
        result = hev_socks5_tunnel_main_from_str (
            config_bytes, (unsigned int)config_length, tun_fd);

finished:
    nexo_tunnel_native_finished ();
    mirror_end (borrowed_mirror_fd);
    free (config_bytes);
    if (signal_mask_set) {
        if (!pipe_signal_was_pending)
            sigtimedwait (&blocked, NULL, &no_wait);
        pthread_sigmask (SIG_SETMASK, &old_mask, NULL);
    }
    pthread_mutex_lock (&lifecycle_mutex);
    cancelled = cancel_requested;
    cancel_requested = 0;
    phase = NEXO_IDLE;
    pthread_mutex_unlock (&lifecycle_mutex);
    return cancelled ? -2 : result;
}

JNIEXPORT void JNICALL
Java_br_com_nexotools_network_NativeTunnel_stop (JNIEnv *env, jclass klass,
                                                jlong token)
{
    (void)env;
    (void)klass;
    pthread_mutex_lock (&lifecycle_mutex);
    if ((token > 0) && ((uint64_t)token == current_token) &&
        (phase != NEXO_IDLE)) {
        cancel_requested = 1;
        if (phase == NEXO_PREPARED) {
            phase = NEXO_IDLE;
            current_token = 0;
        } else if (engine_ready) {
            hev_socks5_tunnel_quit ();
        }
    }
    pthread_mutex_unlock (&lifecycle_mutex);
}

JNIEXPORT jlong JNICALL
Java_br_com_nexotools_network_NativeTunnel_dropped (JNIEnv *env, jclass klass)
{
    (void)env;
    (void)klass;
    return (jlong)atomic_load_explicit (&mirror_dropped, memory_order_relaxed);
}

#endif
