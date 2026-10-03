package cn.edu.bjtu.mis.data.thirdparty

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdPartyUpdateReviewTest {
    @Test
    fun eachOriginPurposeIsComparedIndependently() {
        val domain = "https://api.example.com"
        val policies = listOf(
            ThirdPartyOriginDeclaration(connect = listOf(domain)),
            ThirdPartyOriginDeclaration(media = listOf(domain)),
            ThirdPartyOriginDeclaration(frame = listOf(domain)),
            ThirdPartyOriginDeclaration(navigation = listOf(domain)),
        )
        policies.forEachIndexed { oldIndex, previous ->
            policies.forEachIndexed { nextIndex, next ->
                val added = addedThirdPartyOriginPolicies(previous, next)
                assertEquals(if (oldIndex == nextIndex) ThirdPartyOriginDeclaration() else next, added)
            }
        }
    }

    @Test
    fun originReorderingAndDuplicatesDoNotTriggerConfirmation() {
        val first = "https://api.example.com"
        val second = "https://media.example.com"
        val previous = ThirdPartyOriginDeclaration(connect = listOf(first, second))
        val next = ThirdPartyOriginDeclaration(connect = listOf(second, first, first))

        assertTrue(addedThirdPartyOriginPolicies(previous, next).all.isEmpty())
    }

    @Test
    fun unchangedAuthorizedPluginDoesNotRequireSecondConfirmation() {
        val previous = ThirdPartyUpdateReviewInput(
            existingNeedsReview = false,
            existingRuntimeProfile = ThirdPartyRuntimeProfile.ContractV1.value,
            addedRequiredCapabilities = emptySet(),
            addedOptionalCapabilities = emptySet(),
            removedAndroidCapabilities = emptySet(),
            addedOrigins = emptySet(),
        )

        assertFalse(requiresThirdPartyUpdateConfirmation(previous))
    }

    @Test
    fun newCapabilityRequiresIncrementalConfirmation() {
        val previous = ThirdPartyUpdateReviewInput(
            existingNeedsReview = false,
            existingRuntimeProfile = ThirdPartyRuntimeProfile.ContractV1.value,
            addedRequiredCapabilities = setOf("android.device.info@1"),
            addedOptionalCapabilities = emptySet(),
            removedAndroidCapabilities = emptySet(),
            addedOrigins = emptySet(),
        )

        assertTrue(requiresThirdPartyUpdateConfirmation(previous))
    }

    @Test
    fun newRemoteOriginRequiresIncrementalConfirmation() {
        val update = ThirdPartyUpdateReviewInput(
            existingNeedsReview = false,
            existingRuntimeProfile = ThirdPartyRuntimeProfile.ContractV1.value,
            addedRequiredCapabilities = emptySet(),
            addedOptionalCapabilities = emptySet(),
            removedAndroidCapabilities = emptySet(),
            addedOrigins = setOf("https://api.example.edu"),
        )

        assertTrue(requiresThirdPartyUpdateConfirmation(update))
    }

    @Test
    fun existingReviewStateCannotBeBypassedByAnUnchangedPackage() {
        val update = ThirdPartyUpdateReviewInput(
            existingNeedsReview = true,
            existingRuntimeProfile = ThirdPartyRuntimeProfile.ContractV1.value,
            addedRequiredCapabilities = emptySet(),
            addedOptionalCapabilities = emptySet(),
            removedAndroidCapabilities = emptySet(),
            addedOrigins = emptySet(),
        )

        assertTrue(requiresThirdPartyUpdateConfirmation(update))
    }
}
