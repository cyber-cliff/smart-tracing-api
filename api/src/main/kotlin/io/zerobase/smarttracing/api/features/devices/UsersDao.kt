package io.zerobase.smarttracing.api.features.devices

import com.google.inject.Inject
import io.zerobase.smarttracing.api.EntityCreationException
import io.zerobase.smarttracing.api.InvalidIdException
import io.zerobase.smarttracing.api.gremlin.execute
import io.zerobase.smarttracing.api.gremlin.getIfPresent
import io.zerobase.smarttracing.api.now
import io.zerobase.smarttracing.common.LoggerDelegate
import io.zerobase.smarttracing.common.models.ContactInfo
import io.zerobase.smarttracing.common.models.User
import io.zerobase.smarttracing.api.validatePhoneNumber
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource
import org.apache.tinkerpop.gremlin.structure.T
import java.util.*

class UsersDao @Inject constructor(private val graph: GraphTraversalSource) {
    companion object {
        private val log by LoggerDelegate()
    }

    /**
     * Creates a user node and links to device id.
     *
     * @param name name of the user.
     * @param phone phone of the user.
     * @param email email of the user.
     * @param id id of the device used to create it.
     *
     * @returns id of the user.
     */
    fun createUser(name: String?, phone: String?, email: String?, deviceId: String): String {
        phone?.apply { validatePhoneNumber(phone) }
        val id = UUID.randomUUID().toString()
        val deviceVertex = graph.V(deviceId).getIfPresent() ?: throw InvalidIdException(deviceId)
        try {
            graph.addV("USER")
                .property(T.id, id).property("name", name).property("phone", phone).property("email", email)
                .property("deleted", false).property("timestamp", now())
                .addE("OWNS").to(deviceVertex)
                .execute()
            return id
        } catch (ex: Exception) {
            log.error("error creating user for device. device={}", deviceId, ex)
            throw EntityCreationException("Error creating user.", ex)
        }
    }

    /**
     * "Deletes" the user
     *
     * @param id id of the user to delete
     */
    fun deleteUser(id: String) {
        try {
            graph.V(id).property("deleted", true).execute()
        } catch (ex: Exception) {
            log.error("failed to delete user. id={}", id, ex)
            throw ex
        }
    }

    /**
     * Gets the user
     *
     * @param id the id of the user
     *
     * @return User struct
     */
    fun getUser(id: String): User? {
        try {
            val vertex = graph.V(id).has("deleted", false).propertyMap<String>().getIfPresent() ?: return null
            return User(
                id = id,
                name = vertex["name"],
                contactInfo = ContactInfo(phoneNumber = vertex["phone"],  email = vertex["email"])
            )
        } catch (ex: Exception) {
            log.error("error getting user. id={}", ex)
            throw ex
        }
    }
}
