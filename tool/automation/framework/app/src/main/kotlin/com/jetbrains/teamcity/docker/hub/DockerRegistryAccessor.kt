package com.jetbrains.teamcity.docker.hub

import com.jetbrains.teamcity.common.network.HttpRequestsUtilities
import com.jetbrains.teamcity.docker.DockerImage
import com.jetbrains.teamcity.docker.hub.data.DockerRegistryInfoAboutImages
import com.jetbrains.teamcity.docker.hub.data.DockerRepositoryInfo
import com.jetbrains.teamcity.docker.hub.data.DockerhubPersonalAccessToken
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.lang.Exception
import java.lang.IllegalArgumentException
import java.lang.IllegalStateException
import java.net.http.HttpResponse
import java.time.Instant

/**
 * Provides access to Docker registry.
 */
class DockerRegistryAccessor {

    private val uri: String
    private val httpRequestsUtilities: HttpRequestsUtilities = HttpRequestsUtilities()
    private val token: String?
    private val jsonSerializer: Json

    /**
     * Creates DockerRegistryAccessor instance.
     * @param uri - Docker Registry URI
     */
    constructor(uri: String, token: String? = "") {
        this.uri = uri
        this.jsonSerializer = Json {
            // -- remove the necessity to include parsing of unused fields
            ignoreUnknownKeys = true
            // -- parse JSON fields that don't have an assigned serializer into a String, e.g.: Number
            isLenient = true
        }

        // TODO: Move username to parameters
        // Generate session-based PAT. Access to private repos won't work with general token
        this.token = if (token.isNullOrEmpty()) "" else this.getPersonalAccessToken("andreykoltsov", token)
    }

    /**
     * Returns general information about Docker Repository.
     * @param image image for which the "repository" (all associated images: OS, OS Version, arch.) would be ...
     * ... retrieved.
     * @return information about the repository; null in case inaccessible
     */
    public fun getRepositoryInfo(image: DockerImage): DockerRepositoryInfo {
        val registryResponse: HttpResponse<String?> = this.httpRequestsUtilities.getJsonWithAuth("${this.uri}/repositories/${image.repo}/tags/${image.tag}", this.token)
        val result = registryResponse.body() ?: ""

        if (!this.httpRequestsUtilities.isResponseSuccessful(registryResponse) || result.isEmpty()) {
            throw IllegalStateException("Unable to get information about the repository from Docker registry: $registryResponse")
        }
        return jsonSerializer.decodeFromString(result)
    }

    /**
     * Returns information about images within given registry.
     * @param image image from given registry
     * @param pageSize maximal amount of images to be included into Dockerhub's response
     */
    public fun getInfoAboutImagesInRegistry(image: DockerImage, pageSize: Int): DockerRegistryInfoAboutImages? {
        val registryResponse: HttpResponse<String?> = httpRequestsUtilities.getJsonWithAuth("${this.uri}/repositories"
                                                                                                + "/${image.repo}/tags?page_size=$pageSize", this.token)
        val result = registryResponse.body() ?: ""

        if (!this.httpRequestsUtilities.isResponseSuccessful(registryResponse) || result.isEmpty()) {
            throw IllegalStateException("Unable to get information about the repository from Docker registry: $registryResponse")
        }
        return jsonSerializer.decodeFromString(result)
    }

    /**
     * Determines previous image pushed into the registry.
     * @param currentImage original Docker image
     * @param targetOs Operating System for which previous image should be found. Multiple images might have an equal ...
     * ... repository and tags, but different target OS. The size will be different as well.
     * @param osVersion - version of operating system. Used mostly for Windows images.
     */
    public fun getPreviousImages(currentImage: DockerImage, targetOs: String = "linux", osVersion: String? = ""): DockerRepositoryInfo? {

        val registryInfo = this.getInfoAboutImagesInRegistry(currentImage, 50)
        if (registryInfo == null) {
            print("Registry information for given image was not found: $currentImage")
            return null
        }

        // get the TAG of previous image. It might have multiple corresponding images (same tag, but different target OS)
        val previousImageRepository = registryInfo.results
                                                                .filter { (it.name != currentImage.tag) }
                                                                // Remove year from tag, making it comparable
                                                                .filter {
                                                                    try {
                                                                        return@filter it.name.contains(currentImage.tag.split("-", limit=2)[1])
                                                                    } catch (e: Exception) {
                                                                        print("Image name does not match the expected pattern, thus would be filtered out: ${it.name}")
                                                                        return@filter false
                                                                    }
                                                                }
                                                                .maxByOrNull { result -> Instant.parse(result.tagLastPushed) }
        if (previousImageRepository == null) {
            return null
        }

        // filter by target OS
        previousImageRepository.images = previousImageRepository.images.filter { it.os == targetOs }
        if (previousImageRepository.images.isNotEmpty() && !osVersion.isNullOrEmpty()) {
            val imagesFilteredByTarget = previousImageRepository.images.filter { it.osVersion.equals(osVersion) }
            if (imagesFilteredByTarget.isEmpty()) {
                // Logging such event as it's hard to investigate such differences
                println("$currentImage - found previous image - ${previousImageRepository.name}, but OS version is different - $osVersion and ${previousImageRepository.images.first().osVersion}")
            }
            previousImageRepository.images = imagesFilteredByTarget
        }

        return previousImageRepository
    }

    /**
     * Creates a session-based Personal Access Token (PAT) for DockerHub REST API access to private repositories.
     * See: https://docs.docker.com/docker-hub/api/latest/#tag/authentication/operation/PostUsersLogin
     * @param username - Dockerhub Username
     * @param token - access token generated on Dockerhub; alternatively - passport
     * @return token
     */
    fun getPersonalAccessToken(username: String, token: String): String {
        val requestBody = JsonObject(
            mapOf(
                "username" to JsonPrimitive(username),
                "password" to JsonPrimitive(token)
            )
        )
        val response =  httpRequestsUtilities.putJsonWithAuth("${this.uri}/users/login", requestBody.toString())
        if (response.body().isNullOrEmpty()) {
            throw RuntimeException("Unable to obtain Dockerhub session-based personal-access token, status: ${response.statusCode()}")
        }
        if (response.statusCode() == 401) {
            throw IllegalArgumentException("Unable to generate session-based token - provided credentials are incorrect \n ${response.body()}")
        }
        val tokenPayload = response.body() ?: ""
        val authResponseJson  = jsonSerializer.decodeFromString<DockerhubPersonalAccessToken>(tokenPayload)
        return authResponseJson.token
    }
}
