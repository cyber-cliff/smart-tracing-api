package io.zerobase.smarttracing.api

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.common.eventbus.EventBus
import com.google.common.eventbus.Subscribe
import io.dropwizard.Application
import io.dropwizard.testing.ConfigOverride.config
import io.dropwizard.testing.junit5.DropwizardAppExtension
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport
import io.zerobase.smarttracing.api.config.AppConfig
import io.zerobase.smarttracing.api.features.devices.SelfReportedSymptoms
import io.zerobase.smarttracing.api.features.devices.SelfReportedTestResult
import io.zerobase.smarttracing.api.features.organizations.*
import io.zerobase.smarttracing.api.gremlin.execute
import io.zerobase.smarttracing.common.models.Address
import io.zerobase.smarttracing.common.models.Scannable
import io.zerobase.smarttracing.common.models.Symptom
import org.apache.tinkerpop.gremlin.driver.Cluster
import org.apache.tinkerpop.gremlin.driver.remote.DriverRemoteConnection
import org.apache.tinkerpop.gremlin.process.traversal.AnonymousTraversalSource
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.`__`.unfold
import org.apache.tinkerpop.gremlin.process.traversal.step.util.WithOptions
import org.apache.tinkerpop.gremlin.structure.T
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.containers.localstack.LocalStackContainer.Service.*
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import ru.vyarus.dropwizard.guice.injector.lookup.InjectorLookup
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sns.SnsClient
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.util.*
import javax.ws.rs.client.Entity
import javax.ws.rs.core.GenericType
import javax.ws.rs.core.Response
import javax.ws.rs.core.UriBuilder

class KGenericContainer(imageName: String) : GenericContainer<KGenericContainer>(imageName)

@Testcontainers
@ExtendWith(DropwizardExtensionsSupport::class)
class ApiIT {
    companion object {
        @JvmStatic
        @Container
        val database: KGenericContainer = KGenericContainer("tinkerpop/gremlin-server:3.4")
            .withExposedPorts(8182)
            .withClasspathResourceMapping(
                "/tinkergraph-overrides.properties",
                "/opt/gremlin-server/conf/tinkergraph-empty.properties",
                BindMode.READ_ONLY
            )

        @JvmStatic
        @Container
        val aws: KGenericContainer = KGenericContainer("localstack/localstack:0.11.4")
            .withExposedPorts(4566)
            .withEnv("SERVICES", "s3,sns,ses,dynamo,kms,ssm")
            .withEnv("DATA_DIR", "/tmp/localstack/data")
//        val aws: LocalStackContainer = LocalStackContainer().withServices(SES, S3, SNS, DYNAMODB, /*KMS,*/ SSM)

        @JvmStatic
        val app = DropwizardAppExtension(Main::class.java, "src/main/resources/config.yml",
            config("server.connector.port", "0"),
            // turn on regular console logging
            config("logging.appenders[0].threshold", "ALL"),
            // disable json logging (too hard to read in tests)
            config("logging.appenders[1].threshold", "NONE"),
            config("server.requestLog.appenders[0].threshold", "NONE"),
            config("enableAllFeatures", "true"),
            config("allowedOrigins", "'*'"),
            config("database.endpoints.write", database::getContainerIpAddress),
            config("database.port") { "${database.getMappedPort(8182)}" },
            config("database.enableAwsSigner", "false"),
            config("database.enableSsl", "false"),
            config("baseQrCodeLink", "http://zerobase.test"),
            config("aws.ses.region", "us-east-1"),
            config("aws.ses.endpoint") { "http://localhost:${aws.getMappedPort(4566)}" },
            config("aws.s3.region", "us-east-1"),
            config("aws.s3.endpoint") { "http://localhost:${aws.getMappedPort(4566)}" },
            config("aws.sns.region", "us-east-1"),
            config("aws.sns.endpoint") { "http://localhost:${aws.getMappedPort(4566)}" },
            config("eventsTopicArn") {
                val sns = SnsClient.builder()
                    .region(Region.US_EAST_1)
                    .endpointOverride(URI.create("http://localhost:${aws.getMappedPort(4566)}"))
                    .build()
                sns.createTopic { it.name("test-topic") }.topicArn()
            }
        )

        val idWrapperType = object: GenericType<Map<String,String>>(){}
    }

    val events: MutableList<Any> = mutableListOf()
    lateinit var g: GraphTraversalSource

    @BeforeEach
    fun setup() {
        app.objectMapper.registerModule(KotlinModule()).addMixIn(Scannable::class.java, ConstructorFix::class.java)
        g = AnonymousTraversalSource.traversal()
            .withRemote(DriverRemoteConnection.using(Cluster.build()
                .addContactPoints(database.containerIpAddress)
                .port(database.getMappedPort(8182))
                .enableSsl(false)
                .create()))
        val eventBus = InjectorLookup.getInjector(app.getApplication<Application<AppConfig>>()).get()
            .getInstance(EventBus::class.java)
        eventBus.register(this)
    }

    @AfterEach
    fun cleanup() {
        g.close()
    }

    @Test
    fun shouldHaveHealthchecks() {
        val response = app.client().target("http://localhost:${app.adminPort}/admin/healthcheck").request().get()
        assertThat(response.status).isEqualTo(200)
    }

    @Test
    fun shouldCreateOrganization() {
        val request = CreateOrganizationRequest("test-org", Contact("+12225551234", "test@zerobase.io", "Testy McTesterson"),
                Address("123", "Happy St", "Fryburg", "CA", "90210", "USA"),
                hasTestingFacilities = false, hasMultipleSites = false
        )

        val createResponse: Map<String,String> = app.client().target(UriBuilder.fromUri("http://localhost:${app.getPort(0)}")
            .path(OrganizationsResource::class.java)
            .build()).request().post(Entity.json(request), idWrapperType)

        assertThat(createResponse).isNotNull.containsKey("id")

        val sites = app.client().target(UriBuilder.fromUri("http://localhost:${app.getPort(0)}")
            .path("/organizations/{orgId}/sites")
            .build(createResponse["id"])).request()
            .get(object: GenericType<List<SiteResponse>>(){})

        assertThat(sites).isNotNull.isNotEmpty.hasSize(1).first().extracting("name").isEqualTo("Default")

        assertThat(events).isNotEmpty()
    }

    @Test
    fun shouldUpdateSiteName() {
        val orgId = createFake("Organization")
        val siteId = createFake("Site")
        g.V(orgId).addE("OWNS").to(g.V(siteId)).execute()

        val updateResponse = app.client().target(UriBuilder.fromUri("http://localhost:${app.getPort(0)}")
            .path("/organizations/{orgId}/sites/{siteId}/name")
            .build(orgId, siteId)).request()
            .put(Entity.json("New Name"))
        assertThat(updateResponse.status).isEqualTo(204)

        val updatedSites = app.client().target(UriBuilder.fromUri("http://localhost:${app.getPort(0)}")
            .path("/organizations/{orgId}/sites")
            .build(orgId)).request()
            .get(object: GenericType<List<SiteResponse>>(){})

        assertThat(updatedSites).isNotNull.isNotEmpty.hasSize(1).first().extracting("name").isEqualTo("New Name")
    }

    @Test
    fun shouldUpdateScannableName() {
        val orgId: String = createFake("Organization")
        val siteId: String = createFake("Site")
        g.V(orgId).addE("OWNS").to(g.V(siteId)).execute()
        val scannableId: String = createFake("Scannable", mapOf("type" to "QR_CODE"))
        g.V(scannableId).addE("OWNS").from(g.V(siteId)).execute()

        val response: Response = app.client().target(UriBuilder.fromUri("http://localhost:${app.getPort(0)}")
            .path(OrganizationsResource::class.java)
            .path(OrganizationsResource::class.java, "delegateSiteRequest")
            .path(SitesResource::class.java, "delegateScannableRequest")
            .path(ScannablesResource::class.java, "updateName")
            .build(orgId, siteId, scannableId)).request()
            .put(Entity.json("New Name"))
        assertThat(response.status).isEqualTo(204)

        val scannables: List<Scannable> = app.client().target(UriBuilder.fromUri("http://localhost:${app.getPort(0)}")
            .path("/organizations/{orgId}/sites/{siteId}/scannables")
            .build(orgId, siteId)).request()
            .get(String::class.java)
            .let { app.objectMapper.readValue(it) }

        assertThat(scannables).isNotNull.isNotEmpty.hasSize(1).first().extracting("id", "name").contains(scannableId, "New Name")
    }

    @Test
    fun shouldConnectBothEdgesOnSelfReportedTest() {
        val deviceId = createFake("Device")

        val reportId: String? = UriBuilder.fromUri("http://localhost:${app.getPort(0)}")
            .path("/devices/{id}/reports/tests")
            .build(deviceId)
            .let { app.client().target(it) }
            .request()
            .post(
                Entity.json(SelfReportedTestResult(LocalDate.now().minusDays(1), false, Instant.now())),
                idWrapperType
            )["id"]

        assertThat(reportId).isNotNull()

        val report: Map<Any, Any> = g.V(reportId).valueMap<Any>().with(WithOptions.tokens).by(unfold<Any>()).next()

        assertThat(report)
            .containsEntry(T.label, "TestResult")
            .containsEntry("verified", false)
            .containsEntry("testDate", LocalDate.now().minusDays(1).toString())

        val otherVertexes = g.V(reportId).hasLabel("TestResult").bothE("REPORTED", "FOR").otherV().id().toList()
        assertThat(otherVertexes).isNotNull.isNotEmpty.hasSize(2).containsOnly(deviceId)
    }

    @Test
    fun shouldRecordSelfReportedSymptoms() {
        val deviceId = createFake("Device")

        val reportId: String? = UriBuilder.fromUri("http://localhost:${app.getPort(0)}")
            .path("/devices/{id}/reports/symptoms")
            .build(deviceId)
            .let { app.client().target(it) }
            .request()
            .post(
                Entity.json(SelfReportedSymptoms(Instant.now(), symptoms = setOf(Symptom.LOSS_OF_TASTE))),
                idWrapperType
            )["id"]

        assertThat(reportId).isNotNull()

        val report: Map<Any, Any> = g.V(reportId).valueMap<Any>().with(WithOptions.tokens).by(unfold<Any>()).next()

        assertThat(report)
            .containsEntry(T.label, "Symptoms")
            .containsEntry("verified", false)
            .containsEntry("symptoms", listOf("LOSS_OF_TASTE"))

        val otherVertexes = g.V(reportId).hasLabel("Symptoms").bothE("REPORTED", "REPORT_FOR").otherV().id().toList()
        assertThat(otherVertexes).isNotNull.isNotEmpty.hasSize(2).containsOnly(deviceId)
    }

    fun createFake(label: String, properties: Map<String, Any> = mapOf()): String {
        val id = UUID.randomUUID().toString()
        val t = g.addV(label).property(T.id, id)
        properties.forEach { k, v -> t.property(k, v) }
        t.execute()
        return id
    }

    @Subscribe
    fun eventSink(event: Any) {
        events.add(event)
    }
}

abstract class ConstructorFix
    @JsonCreator constructor(
        @JsonProperty("id") id: String,
        @JsonProperty("name") name: String,
        @JsonProperty("type") type: String)
{
}
