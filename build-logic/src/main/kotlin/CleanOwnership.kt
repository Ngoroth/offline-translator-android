import org.gradle.api.file.DirectoryProperty
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.File

/** Hold the verification lock before any clean dependency can remove active APKs or reports. */
abstract class CleanOwnership: BuildService<CleanOwnership.Parameters>,AutoCloseable {
    interface Parameters: BuildServiceParameters {val repo: DirectoryProperty}
    @Volatile private var workspace: RunWorkspace?=null
    fun arm() {
        val owner=RunWorkspace(parameters.repo.get().asFile.canonicalFile)
        try {
            require(!owner.journal.exists()) {"Cleanup is unverified; recover the owned run with verifyTranslator before clean"}
            workspace=owner
        } catch(failure: Throwable) {owner.close();throw failure}
    }
    fun cleanRoot() {
        RootBuildCleanup.clear(requireNotNull(workspace))
    }
    override fun close() {workspace?.close();workspace=null}
}

object RootBuildCleanup {
    fun clear(owner: RunWorkspace) {
        val build=File(owner.repo,"build")
        build.listFiles()?.filter {it.name!="translator-verification.lock"}?.forEach {RunWorkspace.safeDelete(it,build)}
        // Keep the lock inode stable so a competitor can never acquire a replacement lock.
    }
}
