package cn.edu.bjtu.mis.data.thirdparty

internal object PluginAppAutoStartPolicy {
    const val CAPABILITY = "android.app.autoStart@1"

    fun isEligible(service: ThirdPartyService): Boolean =
        service.canRun && service.publisherSubjectId.isNotBlank() &&
            THIRD_PARTY_RUNTIME_VERSION >= 4 &&
            CAPABILITY in service.manifest.optionalCapabilities &&
            CAPABILITY !in service.manifest.requiredCapabilities &&
            CAPABILITY in service.grantedCapabilities
}
