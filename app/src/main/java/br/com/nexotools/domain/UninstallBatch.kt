package br.com.nexotools.domain

data class UninstallBatch(
    val remaining: List<String> = emptyList(),
    val inFlight: String? = null,
    val resultReturned: Boolean = false,
    val removed: Int = 0,
    val total: Int = 0,
) {
    val running: Boolean get() = remaining.isNotEmpty() || inFlight != null

    fun launched(packageName: String): UninstallBatch =
        if (inFlight == null && remaining.firstOrNull() == packageName) copy(inFlight = packageName) else this

    fun returned(): UninstallBatch = if (inFlight != null) copy(resultReturned = true) else this

    fun resolved(packageName: String, stillInstalled: Boolean): UninstallBatch = when {
        inFlight != packageName || !resultReturned -> this
        stillInstalled -> copy(remaining = emptyList(), inFlight = null, resultReturned = false)
        else -> copy(remaining = remaining.drop(1), inFlight = null, resultReturned = false, removed = removed + 1)
    }

    fun stop(): UninstallBatch = copy(remaining = emptyList())

    companion object {
        fun start(packages: List<String>): UninstallBatch {
            val selected = packages.filter { it.isNotBlank() }.distinct()
            return UninstallBatch(remaining = selected, total = selected.size)
        }
    }
}
