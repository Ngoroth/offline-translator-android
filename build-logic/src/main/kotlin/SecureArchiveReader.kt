import com.example.offlinetranslator.testkit.*
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.InputStream
import java.util.Locale

object SecureArchiveReader {
    fun extract(model: EvaluationModel, archive: File, destination: File,deadline: Long = System.nanoTime()+900_000_000_000L) {
        require(!destination.exists() || destination.list().isNullOrEmpty())
        destination.mkdirs()
        val expected = model.files.associateBy(ModelFile::path)
        if (model.json.getString("format")=="raw") {
            val spec=model.files.single()
            archive.copyTo(File(destination,spec.path))
            Integrity.verify(destination,spec.path,spec.bytes,spec.sha256)
            return
        }
        val seen=HashSet<String>(); val extracted=HashSet<String>(); var total=0L; var count=0
        val prefix=model.json.getString("rootPrefix")
        archive.inputStream().buffered(65536).use { raw -> TarArchiveInputStream(TarQuotaInputStream(BZip2CompressorInputStream(raw),model.json.getInt("maxEntries"),model.json.getLong("maxUnpackedBytes"),deadline)).use { tar ->
            while (true) {
                val entry=tar.nextEntry ?: break
                val name=if (entry.isDirectory) entry.name.removeSuffix("/") else entry.name
                require(Integrity.safePath(name) && seen.add(name.lowercase(Locale.ROOT)) && ++count<=model.json.getInt("maxEntries")) { "Unsafe/duplicate archive path/count" }
                require(!entry.isSymbolicLink && !entry.isLink && (entry.isDirectory || entry.isFile) && !entry.isCharacterDevice && !entry.isBlockDevice && !entry.isFIFO)
                require(entry.size>=0 && total<=model.json.getLong("maxUnpackedBytes")-entry.size)
                total+=entry.size
                require(name==prefix || name.startsWith("$prefix/"))
                if (entry.isDirectory) continue
                val relative=name.removePrefix("$prefix/")
                require(!relative.endsWith(".tar") && !relative.endsWith(".tar.bz2") && !relative.endsWith(".zip"))
                val spec=expected[relative]
                if (spec!=null) {
                    require(entry.size==spec.bytes)
                    val output=File(destination,relative); output.parentFile.mkdirs()
                    copyExactly(tar,output,spec.bytes)
                    Integrity.verify(destination,relative,spec.bytes,spec.sha256)
                    extracted.add(relative)
                } else drainExactly(tar,entry.size)
            }
        } }
        require(extracted==expected.keys) { "Missing consumed model file" }
    }
    private fun copyExactly(input: InputStream, target: File, bytes: Long) {
        target.outputStream().use { output ->
            var remaining=bytes; val buffer=ByteArray(65536)
            while (remaining>0) { val count=input.read(buffer,0,minOf(remaining,buffer.size.toLong()).toInt()); require(count>0); output.write(buffer,0,count); remaining-=count }
        }
    }
    private fun drainExactly(input: InputStream, bytes: Long) {
        var remaining=bytes; val buffer=ByteArray(65536)
        while (remaining>0) { val count=input.read(buffer,0,minOf(remaining,buffer.size.toLong()).toInt()); require(count>0); remaining-=count }
    }
}
