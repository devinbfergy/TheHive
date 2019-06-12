package org.thp.thehive.controllers.v0

import java.util.Date

import akka.stream.Materializer
import org.specs2.mock.Mockito
import org.specs2.specification.core.{Fragment, Fragments}
import org.thp.scalligraph.AppBuilder
import org.thp.scalligraph.auth.UserSrv
import org.thp.scalligraph.controllers.{AuthenticateSrv, TestAuthenticateSrv}
import org.thp.scalligraph.models.{Database, DatabaseProviders, DummyUserSrv, Schema}
import org.thp.scalligraph.services.{LocalFileSystemStorageSrv, StorageSrv}
import org.thp.thehive.dto.v0._
import org.thp.thehive.models._
import org.thp.thehive.services.LocalUserSrv
import play.api.libs.json.{JsObject, JsString, JsValue, Json}
import play.api.test.{FakeRequest, PlaySpecification}
import play.api.{Configuration, Environment}

case class TestCase(
    caseId: Int,
    title: String,
    description: String,
    severity: Int,
    startDate: Date,
    endDate: Option[Date] = None,
    tags: Set[String] = Set.empty,
    flag: Boolean,
    tlp: Int,
    pap: Int,
    status: String,
    summary: Option[String] = None,
    owner: Option[String],
    customFields: Set[OutputCustomFieldValue] = Set.empty,
    stats: JsValue
)

object TestCase {

  def apply(outputCase: OutputCase): TestCase =
    TestCase(
      outputCase.caseId,
      outputCase.title,
      outputCase.description,
      outputCase.severity,
      outputCase.startDate,
      outputCase.endDate,
      outputCase.tags,
      outputCase.flag,
      outputCase.tlp,
      outputCase.pap,
      outputCase.status,
      outputCase.summary,
      outputCase.owner,
      outputCase.customFields,
      outputCase.stats
    )
}

class CaseCtrlTest extends PlaySpecification with Mockito {
  val dummyUserSrv          = DummyUserSrv(permissions = Permissions.all)
  val config: Configuration = Configuration.load(Environment.simple())

  Fragments.foreach(new DatabaseProviders(config).list) { dbProvider ⇒
    val app: AppBuilder = AppBuilder()
      .bind[UserSrv, LocalUserSrv]
      .bindToProvider(dbProvider)
      .bind[AuthenticateSrv, TestAuthenticateSrv]
      .bind[StorageSrv, LocalFileSystemStorageSrv]
      .bind[Schema, TheHiveSchema]
      .addConfiguration("play.modules.disabled = [org.thp.scalligraph.ScalligraphModule, org.thp.thehive.TheHiveModule]")
    step(setupDatabase(app)) ^ specs(dbProvider.name, app) ^ step(teardownDatabase(app))
  }

  def setupDatabase(app: AppBuilder): Unit =
    app.instanceOf[DatabaseBuilder].build()(app.instanceOf[Database], dummyUserSrv.initialAuthContext)

  def teardownDatabase(app: AppBuilder): Unit = app.instanceOf[Database].drop()

  def specs(name: String, app: AppBuilder): Fragment = {
    val caseCtrl: CaseCtrl              = app.instanceOf[CaseCtrl]
    implicit lazy val mat: Materializer = app.instanceOf[Materializer]

    s"[$name] case controller" should {

      "create a new case from spam template" in {
        val now = new Date()

        val outputCustomFields = Set(
          OutputCustomFieldValue("boolean1", "boolean custom field", "boolean", Some("true")),
          OutputCustomFieldValue("string1", "string custom field", "string", Some("string custom field"))
        )
        val inputCustomFields = Seq(
          InputCustomFieldValue("boolean1", Some(true)),
          InputCustomFieldValue("string1", Some("string custom field"))
        )

        val request = FakeRequest("POST", "/api/v0/case")
          .withJsonBody(
            Json
              .toJson(
                InputCase(
                  title = "case title (create case test)",
                  description = "case description (create case test)",
                  severity = Some(2),
                  startDate = Some(now),
                  tags = Set("tag1", "tag2"),
                  flag = Some(false),
                  tlp = Some(1),
                  pap = Some(3),
                  customFieldValue = inputCustomFields
                )
              )
              .as[JsObject] + ("caseTemplate" → JsString("spam"))
          )
          .withHeaders("user" → "user1")

        val result           = caseCtrl.create(request)
        val resultCase       = contentAsJson(result)
        val resultCaseOutput = resultCase.as[OutputCase]
        val expected = TestCase(
          caseId = resultCaseOutput.caseId,
          title = "[SPAM] case title (create case test)",
          description = "case description (create case test)",
          severity = 2,
          startDate = now,
          endDate = None,
          flag = false,
          tlp = 1,
          pap = 3,
          status = "Open",
          tags = Set("spam", "src:mail", "tag1", "tag2"),
          summary = None,
          owner = None,
          customFields = outputCustomFields,
          stats = Json.obj()
        )

        TestCase(resultCaseOutput) shouldEqual expected
      }

      "create a new case from scratch" in {
        val request = FakeRequest("POST", "/api/v0/case")
          .withJsonBody(
            Json
              .parse(
                """{
                     "status":"Open",
                     "severity":2,
                     "tlp":2,
                     "pap":2,
                     "title":"test 6",
                     "description":"desc ok",
                     "tags":[],
                     "tasks":[
                        {
                           "title":"task x",
                           "flag":false,
                           "status":"Waiting"
                        }
                     ]
                  }"""
              )
              .as[JsObject]
          )
          .withHeaders("user" → "user1")

        val result = caseCtrl.create(request)

        status(result) shouldEqual 201

        val requestList = FakeRequest("GET", "/api/case/task").withHeaders("user" → "user1")
        val resultList  = app.instanceOf[TaskCtrl].list(requestList)

        status(resultList) shouldEqual 200

        val tasksList = contentAsJson(resultList).as[Seq[OutputTask]]

        tasksList.find(_.title == "task x") shouldNotEqual None
      }

      "try to get a case" in {
        val request = FakeRequest("GET", s"/api/v0/case/#2")
          .withHeaders("user" → "user1")
        val result = caseCtrl.get("#145")(request)

        status(result) shouldEqual 404

        val result2          = caseCtrl.get("#2")(request)
        val resultCase       = contentAsJson(result2)
        val resultCaseOutput = resultCase.as[OutputCase]

        val expected = TestCase(
          caseId = 2,
          title = "case#2",
          description = "description of case #2",
          severity = 2,
          startDate = new Date(1531667370000L),
          endDate = None,
          flag = false,
          tlp = 2,
          pap = 2,
          status = "Open",
          tags = Set.empty,
          summary = None,
          owner = Some("user2"),
          customFields = Set.empty,
          stats = Json.obj()
        )

        TestCase(resultCaseOutput) must_=== expected
      }

      "update a case properly" in {
        val request = FakeRequest("PATCH", s"/api/v0/case/#1")
          .withHeaders("user" → "user1")
          .withJsonBody(
            Json.obj(
              "title"  → "new title",
              "flag"   → false,
              "tlp"    → 2,
              "pap"    → 1,
              "status" → "Resolved",
              "tags"   → List("tag1")
            )
          )
        val result = caseCtrl.update("#1")(request)
        status(result) must_=== 200
        val resultCase       = contentAsJson(result)
        val resultCaseOutput = resultCase.as[OutputCase]

        val expected = TestCase(
          caseId = 1,
          title = "new title",
          description = "description of case #1",
          severity = 2,
          startDate = new Date(1531667370000L),
          endDate = None,
          flag = false,
          tlp = 2,
          pap = 1,
          status = "Resolved",
          tags = Set("tag1"),
          summary = None,
          owner = Some("user1"),
          customFields = Set.empty,
          stats = Json.obj()
        )

        TestCase(resultCaseOutput) shouldEqual expected
      }

      "update a bulk of cases properly" in {
        val request = FakeRequest("PATCH", s"/api/v0/case/_bulk")
          .withHeaders("user" → "user1")
          .withJsonBody(
            Json.obj(
              "ids"    → List("#1", "#2"),
              "title"  → "new title edited",
              "flag"   → true,
              "tlp"    → 3,
              "pap"    → 2,
              "status" → "Open",
              "tags"   → List("tag2")
            )
          )
        val result = caseCtrl.bulkUpdate(request)
        status(result) must_=== 200
        val resultCase       = contentAsJson(result)
        val resultCaseOutput = resultCase.as[List[OutputCase]]

        resultCaseOutput.length shouldEqual 2

        val requestGet = FakeRequest("GET", s"/api/v0/case/#1")
          .withHeaders("user" → "user2")
        val resultGet = caseCtrl.get("#1")(requestGet)

        status(resultGet) shouldEqual 200

        val resultCaseGet4 = contentAsJson(resultGet).as[OutputCase]

        resultCaseGet4.title shouldEqual "new title edited"
        resultCaseGet4.flag must beTrue
        resultCaseGet4.tlp shouldEqual 3
        resultCaseGet4.pap shouldEqual 2
        resultCaseGet4.status shouldEqual "Open"
        resultCaseGet4.tags shouldEqual Set("tag2", "tag1")

        val resultGet2 = caseCtrl.get("#2")(requestGet)

        status(resultGet2) shouldEqual 200

        val resultCaseGet2 = contentAsJson(resultGet).as[OutputCase]

        resultCaseGet2.title shouldEqual "new title edited"
        resultCaseGet2.flag must beTrue
        resultCaseGet2.tlp shouldEqual 3
        resultCaseGet2.pap shouldEqual 2
        resultCaseGet2.status shouldEqual "Open"
        resultCaseGet2.tags shouldEqual Set("tag2", "tag1")
      }

      "search cases" in {
        val request = FakeRequest("POST", s"/api/v0/case/_search?range=0-15&sort=-flag&sort=-startDate&nstats=true")
          .withHeaders("user" → "user1")
          .withJsonBody(
            Json.parse("""{"query":{"severity":2}}""")
          )
        val result = caseCtrl.search()(request)
        status(result) must_=== 200
        header("X-Total", result) must beSome("3")
        val resultCases = contentAsJson(result).as[Seq[OutputCase]].map(TestCase.apply)

        val case3 = TestCase(
          caseId = 3,
          title = "case#3",
          description = "description of case #3",
          severity = 2,
          startDate = new Date(1531667370000L),
          endDate = None,
          flag = false,
          tlp = 2,
          pap = 2,
          status = "Open",
          tags = Set("t1", "t2"),
          summary = None,
          owner = Some("user1"),
          customFields = Set(
            OutputCustomFieldValue("boolean1", "boolean custom field", "boolean", Some("true")),
            OutputCustomFieldValue("string1", "string custom field", "string", Some("string1 custom field"))
          ),
          stats = Json.obj()
        )

        resultCases must contain(case3)
      }

      "get and aggregate properly case stats" in {
        val request = FakeRequest("POST", s"/api/v0/case/_stats")
          .withHeaders("user" → "user3")
          .withJsonBody(
            Json.parse("""{
                            "query": {},
                            "stats":[
                               {
                                  "_agg":"field",
                                  "_field":"tags",
                                  "_select":[
                                     {
                                        "_agg":"count"
                                     }
                                  ],
                                  "_size":1000
                               },
                               {
                                  "_agg":"count"
                               }
                            ]
                         }""")
          )
        val result = caseCtrl.stats()(request)
        status(result) must_=== 200
        val resultCase = contentAsJson(result)

        resultCase("count").as[Int] shouldEqual 2
        (resultCase \ "t1" \ "count").get.as[Int] shouldEqual 2
        (resultCase \ "t2" \ "count").get.as[Int] shouldEqual 1
      }

      "assign a case to an user" in {
        val request = FakeRequest("PATCH", s"/api/v0/case/#4")
          .withHeaders("user" → "user2")
          .withJsonBody(Json.obj("owner" → "user2"))
        val result = caseCtrl.update("#4")(request)
        status(result) must_=== 200
        val resultCase       = contentAsJson(result)
        val resultCaseOutput = resultCase.as[OutputCase]

        resultCaseOutput.owner should beSome("user2")
      }

      "force delete a case" in {
        val request = FakeRequest("GET", s"/api/v0/case/#1")
          .withHeaders("user" → "user1")
        val result = caseCtrl.get("#1")(request)

        status(result) shouldEqual 200

        val requestDel = FakeRequest("DELETE", s"/api/v0/case/#1/force")
          .withHeaders("user" → "user1")
        val resultDel = caseCtrl.realDelete("#1")(requestDel)

        status(resultDel) shouldEqual 204
        status(caseCtrl.get("#1")(request)) shouldEqual 404
      }
    }
  }
}