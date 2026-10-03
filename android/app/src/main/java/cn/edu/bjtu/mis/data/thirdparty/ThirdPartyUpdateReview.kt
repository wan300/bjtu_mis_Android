package cn.edu.bjtu.mis.data.thirdparty

internal fun addedThirdPartyOriginPolicies(
    previous: ThirdPartyOriginDeclaration,
    next: ThirdPartyOriginDeclaration,
): ThirdPartyOriginDeclaration = ThirdPartyOriginDeclaration(
    connect = (next.connect.toSet() - previous.connect.toSet()).sorted(),
    media = (next.media.toSet() - previous.media.toSet()).sorted(),
    frame = (next.frame.toSet() - previous.frame.toSet()).sorted(),
    navigation = (next.navigation.toSet() - previous.navigation.toSet()).sorted(),
)

data class ThirdPartyUpdateReviewInput(
    val existingNeedsReview: Boolean,
    val existingRuntimeProfile: String?,
    val addedRequiredCapabilities: Set<String>,
    val addedOptionalCapabilities: Set<String>,
    val removedAndroidCapabilities: Set<String>,
    val addedOrigins: Set<String>,
)

fun requiresThirdPartyUpdateConfirmation(input: ThirdPartyUpdateReviewInput): Boolean =
    input.existingNeedsReview ||
        input.existingRuntimeProfile != ThirdPartyRuntimeProfile.ContractV1.value ||
        input.addedRequiredCapabilities.isNotEmpty() ||
        input.addedOptionalCapabilities.isNotEmpty() ||
        input.removedAndroidCapabilities.isNotEmpty() ||
        input.addedOrigins.isNotEmpty()
