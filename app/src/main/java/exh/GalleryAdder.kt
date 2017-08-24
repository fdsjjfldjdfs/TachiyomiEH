package exh

import android.net.Uri
import com.github.salomonbrys.kotson.*
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.util.syncChaptersWithSource
import exh.metadata.MetadataHelper
import exh.metadata.copyTo
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import timber.log.Timber
import uy.kohesive.injekt.injectLazy
import java.net.MalformedURLException
import java.net.URI
import java.net.URISyntaxException

class GalleryAdder {

    private val db: DatabaseHelper by injectLazy()

    private val sourceManager: SourceManager by injectLazy()

    private val metadataHelper = MetadataHelper()

    private val networkHelper: NetworkHelper by injectLazy()

    companion object {
        const val API_BASE = "https://api.e-hentai.org/api.php"
        val JSON = MediaType.parse("application/json; charset=utf-8")!!
    }

    fun getGalleryUrlFromPage(url: String): String {
        val uri = Uri.parse(url)
        val lastSplit = uri.pathSegments.last().split("-")
        val pageNum = lastSplit.last()
        val gallery = lastSplit.first()
        val pageToken = uri.pathSegments.elementAt(1)

        val json = JsonObject()
        json["method"] = "gtoken"
        json["pagelist"] = JsonArray().apply {
            add(JsonArray().apply {
                add(gallery.toInt())
                add(pageToken)
                add(pageNum.toInt())
            })
        }

        val outJson = JsonParser().parse(networkHelper.client.newCall(Request.Builder()
                .url(API_BASE)
                .post(RequestBody.create(JSON, json.toString()))
                .build()).execute().body().string()).obj

        val obj = outJson["tokenlist"].array.first()
        return "${uri.scheme}://${uri.host}/g/${obj["gid"].int}/${obj["token"].string}/"
    }

    fun addGallery(url: String, fav: Boolean = false): Manga {
        val urlObj = Uri.parse(url)
        val source = when(urlObj.host) {
            "g.e-hentai.org", "e-hentai.org" -> EH_SOURCE_ID
            "exhentai.org" -> EXH_SOURCE_ID
            else -> throw MalformedURLException("Not a valid gallery URL!")
        }

        val realUrl = when (urlObj.pathSegments.first().toLowerCase()) {
            "g" -> {
                //Is already gallery page, do nothing
                url
            }
            "s" -> {
                //Is page, fetch gallery token and use that
                getGalleryUrlFromPage(url)
            }
            else -> {
                throw MalformedURLException("Not a valid gallery URL!")
            }
        }

        val sourceObj = sourceManager.get(source)
                ?: throw IllegalStateException("Could not find EH source!")

        val pathOnlyUrl = getUrlWithoutDomain(realUrl)

        //Use manga in DB if possible, otherwise, make a new manga
        val manga = db.getManga(pathOnlyUrl, source).executeAsBlocking()
                ?: Manga.create(source).apply {
            this.url = pathOnlyUrl
            title = realUrl
        }

        //Copy basics
        manga.copyFrom(sourceObj.fetchMangaDetails(manga).toBlocking().first())

        //Apply metadata
        metadataHelper.fetchEhMetadata(realUrl, isExSource(source))?.copyTo(manga)

        if(fav) manga.favorite = true

        db.insertManga(manga).executeAsBlocking().insertedId()?.let {
            manga.id = it
        }

        //Fetch and copy chapters
        try {
            sourceObj.fetchChapterList(manga).map {
                syncChaptersWithSource(db, it, manga, sourceObj)
            }.toBlocking().first()
        } catch (e: Exception) {
            Timber.w(e, "Failed to update chapters for gallery: ${manga.title}!")
        }

        return manga
    }

    private fun getUrlWithoutDomain(orig: String): String {
        try {
            val uri = URI(orig)
            var out = uri.path
            if (uri.query != null)
                out += "?" + uri.query
            if (uri.fragment != null)
                out += "#" + uri.fragment
            return out
        } catch (e: URISyntaxException) {
            return orig
        }
    }
}