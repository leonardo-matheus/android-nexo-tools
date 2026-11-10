package br.com.nexotools.domain

data class InstalledUserApp(val packageName: String, val label: String)

object InstalledAppRules {
    fun isCandidate(packageName: String, ownPackage: String, system: Boolean, systemUpdate: Boolean): Boolean =
        packageName.isNotBlank() && packageName != ownPackage && !system && !systemUpdate

    fun matches(app: InstalledUserApp, query: String): Boolean {
        val term = query.trim()
        return term.isEmpty() || app.label.contains(term, ignoreCase = true) ||
            app.packageName.contains(term, ignoreCase = true)
    }
}

