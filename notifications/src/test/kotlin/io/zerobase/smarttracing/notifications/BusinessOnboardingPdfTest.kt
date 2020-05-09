package io.zerobase.smarttracing.notifications

import com.google.common.io.Resources
import io.zerobase.smarttracing.common.models.Address
import io.zerobase.smarttracing.common.models.ContactInfo
import io.zerobase.smarttracing.common.models.Organization
import io.zerobase.smarttracing.notifications.pdf.DocumentFactory
import io.zerobase.smarttracing.notifications.qr.QRCodeGenerator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.thymeleaf.TemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.w3c.tidy.Tidy
import java.net.URI
import java.nio.charset.StandardCharsets

class BusinessOnboardingPdfTest {
    val org = Organization(
        id = "hello",
        name = "org",
        address = Address("123", "Happy St", "Fryburg", "CA", "90210", "USA"),
        contactName = "Bob",
        contactInfo = ContactInfo(phoneNumber = "+12225551234", email = "bob@zb-test.io")
    )

    val qrCodeGenerator = QRCodeGenerator(
        URI.create("http://zb.test"),
        Resources.getResource("qr/qr-code-logo.png")
    )

    val documentFactory = DocumentFactory(
        TemplateEngine().apply {
            templateResolvers = setOf(ClassLoaderTemplateResolver().apply {
                suffix = ".html"
                characterEncoding = StandardCharsets.UTF_8.displayName()
            })
        },
        Tidy().apply {
            inputEncoding = StandardCharsets.UTF_8.displayName()
            outputEncoding = StandardCharsets.UTF_8.displayName()
            xhtml = true
        }
    )

    @Test
    fun shouldGenerateQrConsistently() {
        val code1 = qrCodeGenerator.generate("test-code")
        val code2 = qrCodeGenerator.generate("test-code")

        assertThat(code1).isEqualTo(code2)
    }

    @Test
    fun shouldGeneratePdfConsistently() {
        val qrCode = qrCodeGenerator.generate("test-code")
        documentFactory.siteOnboarding(org, qrCode).render()
        // no exceptions
    }
}
