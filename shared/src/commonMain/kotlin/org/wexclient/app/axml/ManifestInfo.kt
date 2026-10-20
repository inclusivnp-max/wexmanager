package org.wexclient.app.axml

data class ManifestInfo(
    val packageName: String = "",
    val versionCode: String = "",
    val versionName: String = "",
    val minSdkVersion: String = "",
    val targetSdkVersion: String = "",
    val permissions: List<String> = emptyList(),
    val activities: List<String> = emptyList(),
    val launcherActivity: String? = null
) {
    companion object {
        fun parse(xmlContent: String): ManifestInfo {
            var pkg = ""
            var vCode = ""
            var vName = ""
            var minSdk = ""
            var targetSdk = ""
            val permissions = mutableListOf<String>()
            val activities = mutableListOf<String>()
            var launcher: String? = null

            val pkgMatch = Regex("package=\"([^\"]+)\"").find(xmlContent)
            if (pkgMatch != null) pkg = pkgMatch.groupValues[1]

            val vCodeMatch = Regex("versionCode=\"([^\"]+)\"").find(xmlContent)
            if (vCodeMatch != null) vCode = vCodeMatch.groupValues[1]

            val vNameMatch = Regex("versionName=\"([^\"]+)\"").find(xmlContent)
            if (vNameMatch != null) vName = vNameMatch.groupValues[1]

            val minSdkMatch = Regex("minSdkVersion=\"([^\"]+)\"").find(xmlContent)
            if (minSdkMatch != null) minSdk = minSdkMatch.groupValues[1]

            val targetSdkMatch = Regex("targetSdkVersion=\"([^\"]+)\"").find(xmlContent)
            if (targetSdkMatch != null) targetSdk = targetSdkMatch.groupValues[1]

            Regex("<uses-permission[^>]+name=\"([^\"]+)\"").findAll(xmlContent).forEach { match ->
                permissions.add(match.groupValues[1])
            }

            Regex("<activity[^>]+name=\"([^\"]+)\"").findAll(xmlContent).forEach { match ->
                val actName = match.groupValues[1]
                activities.add(actName)
            }

            val launcherMatch = Regex("<activity[^>]+name=\"([^\"]+)\"[^>]*>(?:(?!</activity>).)*action\\.MAIN(?:(?!</activity>).)*category\\.LAUNCHER", RegexOption.DOT_MATCHES_ALL).find(xmlContent)
            if (launcherMatch != null) {
                launcher = launcherMatch.groupValues[1]
            }

            return ManifestInfo(
                packageName = pkg,
                versionCode = vCode,
                versionName = vName,
                minSdkVersion = minSdk,
                targetSdkVersion = targetSdk,
                permissions = permissions,
                activities = activities,
                launcherActivity = launcher
            )
        }
    }
}
