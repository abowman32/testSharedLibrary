import groovy.json.*
import org.apache.http.client.methods.*
import org.apache.http.entity.*
import org.apache.http.impl.client.*
import groovy.io.FileType
import groovy.json.*
import org.apache.http.client.methods.*
import org.apache.http.entity.*
import org.apache.http.impl.client.*
import au.com.bytecode.opencsv.CSVReader
import com.cloudbees.groovy.cps.NonCPS
import groovy.io.FileType
import hudson.FilePath
import jenkins.model.Jenkins

// def artifactsUrl = "https://esri.tpondemand.com/api/v1/TestPlans/182427/TestCases?include=[Id,Name]%26take=500%26format=json%26access_token=MTEwMDpTS0hvcnVPNW93Y0MwZXBTUGVHMDJveW1FaCtRdHdGbEVlYThWZ1hQY2R3PQ=="
// try {
//   List<String> artifacts = new ArrayList<String>()
//   def get = new HttpGet(artifactsUrl)
//   get.addHeader("content-type","application/json")

//   // execute
//   def client = HttpClientBuilder.create().build()
//   def response = client.execute(get)

//   def bufferedReader = new BufferedReader(new InputStreamReader(response.getEntity().getContent()))
//   def jsonResponse = bufferedReader.getText()
//   //println%26response: \n" + jsonResponse
//   def jsonSlurper = new JsonSlurper()
//   def artifactsJsonObject = jsonSlurper.parseText(jsonResponse)
//   def dataArray = artifactsJsonObject.Items

//   //def build = currentBuild.getRawBuild()
//   //build.addOrReplaceAction(new ParametersAction(MetaDataParameter))

//   for(item in dataArray){
//     artifacts.add(item.Name)
//   }
//   return artifacts
// } catch (Exception e) {
//     print "There was a problem fetching the artifacts" + e
// }

def findDataFile(String Dir, String FName) {
    def files = []
    def directory = new File(Dir)
    def closure = {File f -> if(f.name =~ /${FName}$/) {println f; files << f}; println(f.path) }
    directory.eachFileRecurse FileType.FILES, closure
    return files
}

@NonCPS
def getTestMetadata(){

    //Get list of all test cases selected for execution
    def listTests = "${params.testname}".split(",")
    def listTestGroups = []

    //Group tests in groups of 10
    def strTemp = ""
    def intT = 1
    for(String t : listTests) {
        strTemp = "${strTemp}\"${t}\","

        if((intT % 10) ==0){
            listTestGroups.add(strTemp.toString().substring(0,strTemp.toString().length()-1))
            strTemp = ""
        }

        intT = intT+1
    }

    //add the remaining group
    if(strTemp.toString().length() > 0){
        listTestGroups.add(strTemp.toString().substring(0,strTemp.toString().length()-1))
    }

    //
    def jsonSlurper = new JsonSlurper()
    def dataArray = jsonSlurper.parseText("[]")
    for (String tg : listTestGroups){
        def tpURL = "https://esri.tpondemand.com/api/v1/TestPlans/182427/TestCases?where=(Name%20in%20(" + URLEncoder.encode((tg),"UTF-8") + "))&include=[Name,Tags]&take=25&format=json&access_token=MTEwMDpTS0hvcnVPNW93Y0MwZXBTUGVHMDJveW1FaCtRdHdGbEVlYThWZ1hQY2R3PQ=="
        //println tpURL
        try {
            def get = new HttpGet(tpURL)
            get.addHeader("content-type","application/json")

            // execute
            def client = HttpClientBuilder.create().build()
            def response = client.execute(get)

            def bufferedReader = new BufferedReader(new InputStreamReader(response.getEntity().getContent()))
            def jsonResponse = bufferedReader.getText()
            //println "response: \n" + jsonResponse

            def artifactsJsonObject = jsonSlurper.parseText(jsonResponse)
            dataArray += artifactsJsonObject.Items

        } catch (Exception e) {
            println "There was a problem fetching the artifacts" + e
        }
    }

    //return all test data set
    return JsonOutput.toJson(dataArray)
}

//  SPLIT OUT THE INLINE CODE BELOW INTO MULTIPLE LINES (113-126) FOR TESTING
//    List<String[]> rows = new CSVReader(new FileReader(new File("${workspace}/$DataFile"))).readAll()
    // File file = new File("${workspace}/$DataFile")
    // echo "Does the file exist? " + file.exists()
    // List<String[]> rows = new ArrayList()
    // if(file.exists()) {
    //     echo "File exists at " + file.absolutePath
    //     FileReader fr = new FileReader(file)
    //     if(fr != null){
    //         rows = new CSVReader(fr).readAll()   
    //     }
    // }
    // else
    //     echo "File still not found"
    // echo rows.toString()

@NonCPS
def createTestDictionary(String DataFile, String FileName){

    HashMap<String, String> dicTests = new HashMap<>()
    println("Datafile:${workspace}/$DataFile")
    def fullFilePath = "${workspace}/$DataFile"
    def rows

    node("Linux") {
        listFiles(createFilePath("${workspace}src/test/resources/data/learnarcgis/"))
    }

    def createFilePath(path) {
        return new FilePath(Jenkins.getInstance().getComputer(env['Linux']).getChannel(), path)
    }
    def listFiles(rootPath) {
        print "Files in ${rootPath}:"
        for (subPath in rootPath.list()) { //is this the absolute path?
            echo ${subPath.getName()} 
            def subName = ${subPath.getName()}
            if(FileName.equals(${subName){
                rows = new CSVReader(new FileReader(new File(subPath))).readAll()
            }
        }
    }

    def readRow = 0
    def testColumn = -1
    for (String[] columns : rows){

        //first row get the testname column number
        if(readRow==0){
            def colNum = 0
            for(String col : columns ){
                if(col.toLowerCase().trim() == "testdescription"){
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

    HashMap<String, HashMap<String,String>> dicTestsFiles = new HashMap<>()

    def xmlTestNG = """<?xml version="1.0" encoding="UTF-8"?>
    <!DOCTYPE suite SYSTEM "http://testng.org/testng-1.0.dtd">
    <suite name="{JobName}" preserve-order="true" parallel="tests" thread-count="4">
            {Tests}
    <listeners>
        <listener class-name="com.esri.qa.reporting.ExtentReporter" />
        <listener class-name="com.esri.qa.reporting.ReportListener" />
    </listeners>
    </suite>"""

    def xmlTest = """   <test name='{TestName}'>
    <parameter name="bType" value="chrome" />
        <parameter name="fileName" value="{DataFile}" />
    <parameter name="startRow" value="{StartRow}" />
        <parameter name="endRow" value="{EndRow}" />
    <parameter name="batchName" value="{BatchName}" />
        <classes>
            <class name="{Class}" />
    </classes>
    </test>"""


    //print dicTests
    //formulate the Tests for TestNG XML
    def xmlTests = ""
    def jsonSlurper = new JsonSlurper()
    def testsCollection = jsonSlurper.parseText(tests)
    for(item in testsCollection){

        //Create dictionary object of tags
        String[] Tags = item.Tags.split(",")
        HashMap<String,String> mapTags = new HashMap<>()
        for(String Tag : Tags){
            def val = Tag.split(":",2)
            mapTags.put(val[0].trim().toLowerCase(),val[1].trim())
        }

        //data file path
        String dataFile = "src/test/resources/data/learnarcgis/${mapTags["tdata"]}"
        println(dataFile)
        
        def fileName = mapTags["tdata"]
        if (!dicTestsFiles.containsKey(dataFile)){
            def dic = createTestDictionary(dataFile, fileName)
            dicTestsFiles.put(dataFile, dic)
        }


        String givenTest = dicTestsFiles[dataFile][item.Name.toLowerCase().trim().toString()]
        def xmlT = xmlTest.replace("{StartRow}",givenTest)
        xmlT = xmlT.replace("{EndRow}",givenTest)
        xmlT = xmlT.replace("{DataFile}",dataFile )
        xmlT = xmlT.replace("{Class}",mapTags["tclass"])
        xmlT = xmlT.replace("{TestName}",item.Name.toLowerCase().trim())
        xmlT = xmlT.replace("{BatchName}","${JOB_NAME}-${BUILD_NUMBER}")
        xmlTests = xmlTests + '\n' + xmlT
    }

    xmlTestNG = xmlTestNG.replace("{JobName}","${JOB_NAME}")
    xmlTestNG = xmlTestNG.replace("{Tests}",xmlTests)

    return xmlTestNG.toString()

}


node('Linux') {
    stage('Clone from CodeHub') {
        git branch: "${params.'git.branch'}", url: 'git@github-webpage-testing:/IST-QA/webpage-testing.git'
        //git branch: "${params.'git.branch'}", url: 'git@codehub.esri.com:IST-QA/webpage-testing.git'
    }

    stage('Create TestNG XML') {

        def arr = getTestMetadata()
        println("metadata$arr")
        def TestNGXML = getTestNGXML(arr)
        println("testxml$TestNGXML")
        writeFile encoding: 'utf-8', file: 'TestNG.xml', text: TestNGXML

    }
/*
    stage('Build & Test') {
        def cmd = " -PframeworkVersion=2.0-SNAPSHOT -Pbase.url=${params.'base.url'} -Pgrid.url=${params.'grid.url'} -Preport.dashboard.enabled=${params.'report.dashboard.enabled'} -Pgrid.enabled=${params.'grid.enabled'} -Preport.server=${params.'report.server'} -Pproject.name=${params.'project.name'} -Pversion=2.0-SNAPSHOT -Pscreenshot.on.success=${params.'screenshot.on.success'} -Preport.folder=${params.'report.folder'} -Preport.name=${params.'report.name'} -Pcbt.enabled=${params.'cbt.enabled'} -Ptestngxml=TestNG.xml --refresh-dependencies clean build"
        if (isUnix()) {
            sh "./gradlew ${cmd}"
        } else {
            bat "gradlew.bat ${cmd}"
        }
    }

    stage('Publish results') {

    }*/
}