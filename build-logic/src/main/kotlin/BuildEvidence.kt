import com.example.offlinetranslator.testkit.Integrity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

object BuildEvidence {
    fun suite(file: File): JSONObject {
        require(file.isFile && file.length() in 1..1048576)
        val factory=XMLInputFactory.newFactory()
        factory.setProperty(XMLInputFactory.SUPPORT_DTD,false)
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities",false)
        file.inputStream().use {input ->
            val reader=factory.createXMLStreamReader(input,"UTF-8")
            try {
                while(reader.hasNext()) {
                    val type=reader.next();require(type!=XMLStreamConstants.DTD) {"DTD in test evidence"}
                    if(type==XMLStreamConstants.START_ELEMENT) {
                        require(reader.localName=="testsuite")
                        val row=JSONObject().put("file",file.name).put("sha256",Integrity.sha256(file))
                        for(key in listOf("tests","failures","errors","skipped")) {
                            val count=requireNotNull(reader.getAttributeValue(null,key)).toInt()
                            require(count in 0..10000);row.put(key,count)
                        }
                        require(row.getInt("tests")>0 && row.getInt("failures")==0 && row.getInt("errors")==0 && row.getInt("skipped")==0)
                        return row.put("status","PASS")
                    }
                }
                error("Missing test suite evidence")
            } finally {reader.close()}
        }
    }
    fun collect(repo: File): JSONObject {
        val modules=JSONArray()
        for(module in listOf("core","engine-mlkit","engine-sherpa","app","translator-test-kit","build-logic")) {
            val directory=File(repo,"$module/build/test-results")
            val files=directory.walkTopDown().maxDepth(4).filter {it.isFile && it.name.startsWith("TEST-") && it.extension=="xml"}.take(129).toList()
            require(files.size in 1..128) {"Missing/oversized prerequisite test evidence: $module"}
            modules.put(JSONObject().put("module",module).put("status","PASS").put("suites",JSONArray(files.sortedBy(File::getName).map(::suite))))
        }
        val lint=File(repo,"app/build/reports/lint-results-debug.html")
        require(lint.isFile)
        return JSONObject().put("jvm",modules).put("lint",JSONObject().put("status","PASS").put("reportSha256",Integrity.sha256(lint)))
            .put("runnerAssemblies","PASS")
    }
}
