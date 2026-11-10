#ifndef NEXO_NATIVE_TUNNEL_H
#define NEXO_NATIVE_TUNNEL_H

struct pbuf;

/* Called only by the HEV worker, after init and before fini respectively. */
void nexo_tunnel_native_ready (void);
void nexo_tunnel_native_finished (void);

/* The pbuf is borrowed; this function neither changes nor retains it. */
void nexo_tunnel_observe (const struct pbuf *packet);

#endif
