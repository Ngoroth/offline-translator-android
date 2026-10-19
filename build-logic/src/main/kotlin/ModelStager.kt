import com.example.offlinetranslator.testkit.*
import java.io.File
import java.net.URI
import org.json.JSONObject

class ModelStager(private val workspace: File,private val cache: EvaluationInputCache? = null) {
    fun stage(model: EvaluationModel,runDeadline: Long = Long.MAX_VALUE): JSONObject {
        if(cache!=null) return cached(model,runDeadline)
        val deadline=minOf(System.nanoTime()+900_000_000_000L,runDeadline)
        val archive=File(workspace,"${model.id}.archive")
        val destination=File(workspace,"models/${model.id}")
        try {
            val started=System.nanoTime()
            if (model.json.has("localSource")) {
                val source=Integrity.verify(model.manifestDirectory,model.json.getString("localSource"),model.json.getLong("archiveBytes"),model.json.getString("archiveSha256"))
                java.nio.file.Files.newByteChannel(source.toPath(),setOf(java.nio.file.StandardOpenOption.READ,java.nio.file.LinkOption.NOFOLLOW_LINKS)).use { channel ->
                    PinnedHttps.copy(java.nio.channels.Channels.newInputStream(channel),archive,model.json.getLong("archiveBytes"),deadline)
                }
            }
            else PinnedHttps.download(URI(model.json.getString("url")),
                model.json.getJSONArray("allowedRedirectHosts").let { (0 until it.length()).map(it::getString).toSet() },archive,model.json.getLong("archiveBytes"),deadline)
            val downloaded=System.nanoTime()
            require(archive.length()==model.json.getLong("archiveBytes") && Integrity.sha256(archive)==model.json.getString("archiveSha256")) { "Archive hash/size mismatch" }
            val verified=System.nanoTime()
            require(System.nanoTime()<deadline) {"Model staging deadline exceeded"}
            SecureArchiveReader.extract(model,archive,destination,deadline)
            val extracted=System.nanoTime()
            return JSONObject().put("downloadMs",(downloaded-started)/1_000_000.0).put("archiveVerificationMs",(verified-downloaded)/1_000_000.0)
                .put("extractionAndFileVerificationMs",(extracted-verified)/1_000_000.0).put("totalMs",(extracted-started)/1_000_000.0)
        } finally { archive.delete() }
    }

    private fun cached(model: EvaluationModel,runDeadline: Long): JSONObject {
        val store=requireNotNull(cache)
        val deadline=minOf(System.nanoTime()+900_000_000_000L,runDeadline)
        val start=System.nanoTime()
        // A hit never makes an invalid declared local source acceptable.
        val local=model.json.optString("localSource").takeIf(String::isNotBlank)?.let {
            Integrity.verify(model.manifestDirectory,it,model.json.getLong("archiveBytes"),model.json.getString("archiveSha256"))
        }
        var downloadMs: Double?=null;var extractionMs: Double?=null
        val archiveSpec=ModelFile("archive",model.json.getLong("archiveBytes"),model.json.getString("archiveSha256"))
        val archive=store.obtain("archives",EvaluationInputCache.archiveKey(model),listOf(archiveSpec),archiveSpec.bytes,deadline) { data ->
            val before=System.nanoTime();val target=File(data,"archive")
            if(local!=null) java.nio.file.Files.newByteChannel(local.toPath(),setOf(java.nio.file.StandardOpenOption.READ,java.nio.file.LinkOption.NOFOLLOW_LINKS)).use {
                PinnedHttps.copy(java.nio.channels.Channels.newInputStream(it),target,archiveSpec.bytes,deadline)
            } else PinnedHttps.download(URI(model.json.getString("url")),model.json.getJSONArray("allowedRedirectHosts").let {
                (0 until it.length()).map(it::getString).toSet()
            },target,archiveSpec.bytes,deadline)
            downloadMs=EvaluationInputCache.elapsed(before)
            listOf(archiveSpec)
        }
        val bundle=store.obtain("bundles",EvaluationInputCache.bundleKey(model),model.files,model.files.sumOf(ModelFile::bytes),deadline) { data ->
            val before=System.nanoTime()
            SecureArchiveReader.extract(model,File(archive.directory,"archive"),data,deadline)
            extractionMs=EvaluationInputCache.elapsed(before)
            model.files
        }
        val copyStart=System.nanoTime();val destination=File(workspace,"models/${model.id}")
        require(!destination.exists()) {"Run model destination already exists"}
        bundle.directory.copyRecursively(destination).also {require(it)}
        model.files.forEach { EvaluationInputCache.checkDeadline(deadline);Integrity.verify(destination,it.path,it.bytes,it.sha256) }
        val copyMs=EvaluationInputCache.elapsed(copyStart)
        return JSONObject().put("cacheHit",archive.hit && bundle.hit).put("archiveCacheHit",archive.hit).put("bundleCacheHit",bundle.hit)
            .put("key",EvaluationInputCache.bundleKey(model)).put("downloadMs",downloadMs ?: JSONObject.NULL)
            .put("extractionAndFileVerificationMs",extractionMs ?: JSONObject.NULL).put("skippedReason",if(archive.hit && bundle.hit) "cacheHit" else JSONObject.NULL)
            .put("archiveVerificationMs",archive.verificationMs).put("bundleVerificationMs",bundle.verificationMs)
            .put("verifiedBytes",archive.bytes+bundle.bytes).put("copiedBytes",bundle.bytes).put("copyAndRunVerificationMs",copyMs)
            .put("totalMs",EvaluationInputCache.elapsed(start))
    }
}
