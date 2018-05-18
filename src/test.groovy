import groovy.io.FileType
import groovy.json.*
import org.apache.http.client.methods.*
import org.apache.http.entity.*
import org.apache.http.impl.client.*
import au.com.bytecode.opencsv.CSVReader
import com.cloudbees.groovy.cps.NonCPS
import groovy.io.FileType

def findDataFile(String Dir, String FName) {
    def files = []
    def directory = new File(Dir)
    def closure = {File f -&gt; if(f.name =~ /${FName}$/) {println f; files &lt;&lt; f}; println(f.path) }
    directory.eachFileRecurse FileType.FILES, closure
    return files
}

@NonCPS
def getTestMetadata(){

    //Get list of all test cases selected for execution
    def listTests = &quot;${params.testname}&quot;.split(&quot;,&quot;)
    def listTestGroups = []

    //Group tests in groups of 10
    def strTemp = &quot;&quot;
    def intT = 1
    for(String t : listTests) {
        strTemp = &quot;${strTemp}\&quot;${t}\&quot;,&quot;

        if((intT % 10) ==0){
            listTestGroups.add(strTemp.toString().substring(0,strTemp.toString().length()-1))
            strTemp = &quot;&quot;
        }

        intT = intT+1
    }

    //add the remaining group
    if(strTemp.toString().length() &gt; 0){
        listTestGroups.add(strTemp.toString().substring(0,strTemp.toString().length()-1))
    }

    //
    def jsonSlurper = new JsonSlurper()
    def dataArray = jsonSlurper.parseText(&quot;[]&quot;)
    for (String tg : listTestGroups){
        def tpURL = &quot;https://esri.tpondemand.com/api/v1/TestPlans/182427/TestCases?where=(Name%20in%20(&quot; + URLEncoder.encode((tg),&quot;UTF-8&quot;) + &quot;))&amp;include=[Name,Tags]&amp;take=25&amp;format=json&amp;access_token=MTEwMDpTS0hvcnVPNW93Y0MwZXBTUGVHMDJveW1FaCtRdHdGbEVlYThWZ1hQY2R3PQ==&quot;
        //println tpURL
        try {
            def get = new HttpGet(tpURL)
            get.addHeader(&quot;content-type&quot;,&quot;application/json&quot;)

            // execute
            def client = HttpClientBuilder.create().build()
            def response = client.execute(get)

            def bufferedReader = new BufferedReader(new InputStreamReader(response.getEntity().getContent()))
            def jsonResponse = bufferedReader.getText()
            //println &quot;response: \n&quot; + jsonResponse

            def artifactsJsonObject = jsonSlurper.parseText(jsonResponse)
            dataArray += artifactsJsonObject.Items

        } catch (Exception e) {
            println &quot;There was a problem fetching the artifacts&quot; + e
        }
    }

    //return all test data set
    return JsonOutput.toJson(dataArray)
}

@NonCPS
def createTestDictionary(String DataFile){

    HashMap&lt;String, String&gt; dicTests = new HashMap&lt;&gt;()
    println(&quot;Datafile:${workspace}/$DataFile&quot;)
    List&lt;String[]&gt; rows = new CSVReader(new FileReader(new File(&quot;${workspace}/$DataFile&quot;))).readAll()
    def readRow = 0
    def testColumn = -1
    for (String[] columns : rows){

        //first row get the testname column number
        if(readRow==0){
            def colNum = 0
            for(String col : columns ){
                if(col.toLowerCase().trim() == &quot;testdescription&quot;){
                    testColumn = colNum
                    break
                }
                colNum++
            }
        }
        else{

            dicTests.put(columns[testColumn].toLowerCase().trim(),readRow.toString())
        }

        readRow++
    }
    println(dicTests)
    return dicTests
}

@NonCPS
def getTestNGXML(tests) {

    HashMap&lt;String, HashMap&lt;String,String&gt;&gt; dicTestsFiles = new HashMap&lt;&gt;()

    def xmlTestNG = &quot;&quot;&quot;&lt;?xml version=&quot;1.0&quot; encoding=&quot;UTF-8&quot;?&gt;
    &lt;!DOCTYPE suite SYSTEM &quot;http://testng.org/testng-1.0.dtd&quot;&gt;
    &lt;suite name=&quot;{JobName}&quot; preserve-order=&quot;true&quot; parallel=&quot;tests&quot; thread-count=&quot;4&quot;&gt;
            {Tests}
    &lt;listeners&gt;
        &lt;listener class-name=&quot;com.esri.qa.reporting.ExtentReporter&quot; /&gt;
        &lt;listener class-name=&quot;com.esri.qa.reporting.ReportListener&quot; /&gt;
    &lt;/listeners&gt;
    &lt;/suite&gt;&quot;&quot;&quot;

    def xmlTest = &quot;&quot;&quot;   &lt;test name=&apos;{TestName}&apos;&gt;
    &lt;parameter name=&quot;bType&quot; value=&quot;chrome&quot; /&gt;
        &lt;parameter name=&quot;fileName&quot; value=&quot;{DataFile}&quot; /&gt;
    &lt;parameter name=&quot;startRow&quot; value=&quot;{StartRow}&quot; /&gt;
        &lt;parameter name=&quot;endRow&quot; value=&quot;{EndRow}&quot; /&gt;
    &lt;parameter name=&quot;batchName&quot; value=&quot;{BatchName}&quot; /&gt;
        &lt;classes&gt;
            &lt;class name=&quot;{Class}&quot; /&gt;
    &lt;/classes&gt;
    &lt;/test&gt;&quot;&quot;&quot;


    //print dicTests
    //formulate the Tests for TestNG XML
    def xmlTests = &quot;&quot;
    def jsonSlurper = new JsonSlurper()
    def testsCollection = jsonSlurper.parseText(tests)
    for(item in testsCollection){

        //Create dictionary object of tags
        String[] Tags = item.Tags.split(&quot;,&quot;)
        HashMap&lt;String,String&gt; mapTags = new HashMap&lt;&gt;()
        for(String Tag : Tags){
            def val = Tag.split(&quot;:&quot;,2)
            mapTags.put(val[0].trim().toLowerCase(),val[1].trim())
        }

        //data file path
        String dataFile = &quot;src/test/resources/data/learnarcgis/${mapTags[&quot;tdata&quot;]}&quot;
        println(dataFile)

        if (!dicTestsFiles.containsKey(dataFile)){
            def dic = createTestDictionary(dataFile)
            dicTestsFiles.put(dataFile, dic)
        }


        String givenTest = dicTestsFiles[dataFile][item.Name.toLowerCase().trim().toString()]
        def xmlT = xmlTest.replace(&quot;{StartRow}&quot;,givenTest)
        xmlT = xmlT.replace(&quot;{EndRow}&quot;,givenTest)
        xmlT = xmlT.replace(&quot;{DataFile}&quot;,dataFile )
        xmlT = xmlT.replace(&quot;{Class}&quot;,mapTags[&quot;tclass&quot;])
        xmlT = xmlT.replace(&quot;{TestName}&quot;,item.Name.toLowerCase().trim())
        xmlT = xmlT.replace(&quot;{BatchName}&quot;,&quot;${JOB_NAME}-${BUILD_NUMBER}&quot;)
        xmlTests = xmlTests + &apos;\n&apos; + xmlT
    }

    xmlTestNG = xmlTestNG.replace(&quot;{JobName}&quot;,&quot;${JOB_NAME}&quot;)
    xmlTestNG = xmlTestNG.replace(&quot;{Tests}&quot;,xmlTests)

    return xmlTestNG.toString()

}


node(&apos;Linux&apos;) {
    stage(&apos;Clone from CodeHub&apos;) {
        git branch: &quot;${params.&apos;git.branch&apos;}&quot;, url: &apos;git@github-webpage-testing:/IST-QA/webpage-testing.git&apos;
        //git branch: &quot;${params.&apos;git.branch&apos;}&quot;, url: &apos;git@codehub.esri.com:IST-QA/webpage-testing.git&apos;
    }

    stage(&apos;Create TestNG XML&apos;) {

        def arr = getTestMetadata()
        println(&quot;metadata$arr&quot;)
        def TestNGXML = getTestNGXML(arr)
        println(&quot;testxml$TestNGXML&quot;)
        writeFile encoding: &apos;utf-8&apos;, file: &apos;TestNG.xml&apos;, text: TestNGXML

    }
/*
    stage(&apos;Build &amp; Test&apos;) {
        def cmd = &quot; -PframeworkVersion=2.0-SNAPSHOT -Pbase.url=${params.&apos;base.url&apos;} -Pgrid.url=${params.&apos;grid.url&apos;} -Preport.dashboard.enabled=${params.&apos;report.dashboard.enabled&apos;} -Pgrid.enabled=${params.&apos;grid.enabled&apos;} -Preport.server=${params.&apos;report.server&apos;} -Pproject.name=${params.&apos;project.name&apos;} -Pversion=2.0-SNAPSHOT -Pscreenshot.on.success=${params.&apos;screenshot.on.success&apos;} -Preport.folder=${params.&apos;report.folder&apos;} -Preport.name=${params.&apos;report.name&apos;} -Pcbt.enabled=${params.&apos;cbt.enabled&apos;} -Ptestngxml=TestNG.xml --refresh-dependencies clean build&quot;
        if (isUnix()) {
            sh &quot;./gradlew ${cmd}&quot;
        } else {
            bat &quot;gradlew.bat ${cmd}&quot;
        }
    }

    stage(&apos;Publish results&apos;) {

    }*/
}