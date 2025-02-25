/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.bakery.api

import com.github.tomakehurst.wiremock.WireMockServer
import com.netflix.spinnaker.orca.bakery.config.BakeryConfiguration
import com.netflix.spinnaker.orca.jackson.OrcaObjectMapper
import retrofit.RequestInterceptor
import retrofit.RetrofitError
import retrofit.client.OkClient
import spock.lang.Specification
import spock.lang.Subject
import static com.github.tomakehurst.wiremock.client.WireMock.*
import static com.google.common.net.HttpHeaders.LOCATION
import static java.net.HttpURLConnection.*
import static retrofit.RestAdapter.LogLevel.FULL

class BakeryServiceSpec extends Specification {

  static WireMockServer wireMockServer = new WireMockServer()

  @Subject BakeryService bakery

  private static final region = "us-west-1"
  private static final bake = BakeRequest.Default.copyWith(user: "rfletcher", packageName: "orca")
  private static final bakePath = "/api/v1/$region/bake"
  private static final statusPath = "/api/v1/$region/status"
  private static final bakeId = "b-123456789"
  private static final statusId = "s-123456789"

  static String bakeURI
  static String statusURI

  def mapper = OrcaObjectMapper.newInstance()

  def setupSpec() {
    wireMockServer.start()
    bakeURI = wireMockServer.url(bakePath)
    statusURI = wireMockServer.url(statusPath)
  }

  def setup() {
    bakery = new BakeryConfiguration(
      retrofitClient: new OkClient(),
      retrofitLogLevel: FULL,
      spinnakerRequestInterceptor: Mock(RequestInterceptor)
    )
      .buildService(wireMockServer.url("/"))
  }

  def cleanupSpec() {
    wireMockServer.stop()
  }

  def "can lookup a bake status"() {
    given:
    stubFor(
      get("$statusPath/$statusId")
        .willReturn(
        aResponse()
          .withStatus(HTTP_OK)
          .withBody(mapper.writeValueAsString([
          state       : "COMPLETED",
          progress    : 100,
          status      : "SUCCESS",
          code        : 0,
          resource_id : bakeId,
          resource_uri: "$bakeURI/$bakeId",
          uri         : "$statusURI/$statusId",
          id          : statusId,
          attempts    : 0,
          ctime       : 1382310109766,
          mtime       : 1382310294223,
          messages    : ["amination success"]
        ]))
      )
    )

    expect:
    with(bakery.lookupStatus(region, statusId)) {
      id == statusId
      state == BakeStatus.State.COMPLETED
      resourceId == bakeId
    }
  }

  def "looking up an unknown status id will throw an exception"() {
    given:
    stubFor(
      get("$statusPath/$statusId")
        .willReturn(
        aResponse()
          .withStatus(HTTP_NOT_FOUND)
      )
    )

    when:
    bakery.lookupStatus(region, statusId)

    then:
    def ex = thrown(RetrofitError)
    ex.response.status == HTTP_NOT_FOUND
  }

  def "should return status of newly created bake"() {
    given: "the bakery accepts a new bake"
    stubFor(
      post(bakePath)
        .willReturn(
        aResponse()
          .withStatus(HTTP_ACCEPTED)
          .withBody(mapper.writeValueAsString([
          state       : "PENDING",
          progress    : 0,
          resource_id : bakeId,
          resource_uri: "$bakeURI/$bakeId",
          uri         : "$statusURI/$statusId",
          id          : statusId,
          attempts    : 0,
          ctime       : 1382310109766,
          mtime       : 1382310109766,
          messages    : []
        ]))
      )
    )

    expect: "createBake should return the status of the bake"
    with(bakery.createBake(region, bake, null)) {
      id == statusId
      state == BakeStatus.State.PENDING
      resourceId == bakeId
    }
  }

  def "should handle a repeat create bake response"() {
    given: "the POST to /bake redirects to the status of an existing bake"
    stubFor(
      post(bakePath)
        .willReturn(
        aResponse()
          .withStatus(HTTP_SEE_OTHER)
          .withHeader(LOCATION, "$statusURI/$statusId")
      )
    )
    stubFor(
      get("$statusPath/$statusId")
        .willReturn(
        aResponse()
          .withStatus(HTTP_OK)
          .withBody(mapper.writeValueAsString([
          state       : "RUNNING",
          progress    : 1,
          resource_id : bakeId,
          resource_uri: "$bakeURI/$bakeId",
          uri         : "$statusURI/$statusId",
          id          : statusId,
          attempts    : 1,
          ctime       : 1382310109766,
          mtime       : 1382310109766,
          messages    : ["on instance i-66f5913d runnning: aminate ..."]
        ])
        )
      )
    )

    expect: "createBake should return the status of the bake"
    with(bakery.createBake(region, bake, null)) {
      id == statusId
      state == BakeStatus.State.RUNNING
      resourceId == bakeId
      // TODO: would we actually get a bake id if it was incomplete?
    }
  }

  def "can lookup the details of a bake"() {
    given:
    stubFor(
      get("$bakePath/$bakeId")
        .willReturn(
        aResponse()
          .withStatus(HTTP_OK)
          .withBody(mapper.writeValueAsString([
          ami       : "ami",
          base_ami  : "base_ami",
          ami_suffix: "ami_suffix",
          base_name : "base_name",
          ami_name  : "ami_name",
          id        : bakeId
        ]))
      )
    )

    expect:
    with(bakery.lookupBake(region, bakeId)) {
      id == bakeId
      ami == "ami"
    }
  }
}
