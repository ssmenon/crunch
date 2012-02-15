/**
 * Copyright (c) 2011, Cloudera, Inc. All Rights Reserved.
 *
 * Cloudera, Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"). You may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the
 * License.
 */
package com.cloudera.scrunch

import Avros._

import com.cloudera.crunch.{DoFn, Emitter, Pair => P}
import com.cloudera.crunch.io.{From => from}
import com.cloudera.crunch.test.FileHelper

import scala.collection.mutable.HashMap

import org.scalatest.junit.JUnitSuite
import _root_.org.junit.Assert._
import _root_.org.junit.Test

class CachingPageRankFn extends DoFn[P[String, (Float, Float, List[String])], P[String, Float]] {
  val cache = new HashMap[String, Float] {
    override def default(key: String) = 0f
  }

  override def process(input: P[String, (Float, Float, List[String])], emitFn: Emitter[P[String, Float]]) {
    val (pr, oldpr, urls) = input.second()
    val newpr = pr / urls.size
    urls.foreach(url => cache.put(url, cache(url) + newpr))
    if (cache.size > 5000) {
      cleanup(emitFn)
    }
  }

  override def cleanup(emitFn: Emitter[P[String, Float]]) {
    cache.foreach(kv => emitFn.emit(P.of(kv._1, kv._2)))
    cache.clear
  }
}

class PageRankTest extends JUnitSuite {
  val pipeline = new Pipeline[PageRankTest]

  def initialInput(fileName: String) = {
    pipeline.read(from.textFile(fileName))
      .map(line => { val urls = line.split("\\t"); (urls(0), urls(1)) })
      .groupByKey
      .map((url, links) => (url, (1f, 0f, links.toList)))
  }

  def update(prev: PTable[String, (Float, Float, List[String])], d: Float) = {
    val outbound = prev.flatMap((url, v) => {
      val (pr, oldpr, links) = v
      links.map(link => (link, pr / links.size))
    })
    cg(prev, outbound, d)
  }

  def cg(prev: PTable[String, (Float, Float, List[String])],
         out: PTable[String, Float], d: Float) = {
    prev.cogroup(out).map((url, v) => {
      val (p, o) = v
      val (pr, oldpr, links) = p.head
      (url, ((1 - d) + d * o.sum, pr, links))
    })
  }

  def fastUpdate(prev: PTable[String, (Float, Float, List[String])], d: Float) = {
    val outbound = prev.parallelDo(new CachingPageRankFn(), tableOf(strings, floats))
    cg(prev, outbound, d)
  }

  @Test def testPageRank {
    var prev = initialInput(FileHelper.createTempCopyOf("urls.txt"))
    var delta = 1.0f
    while (delta > 0.01f) {
      prev = update(prev, 0.5f)
      delta = prev.map((k, v) => math.abs(v._1 - v._2)).max.materialize.head
    }
    assertEquals(0.0048, delta, 0.001)
    pipeline.done
  }

  @Test def testFastPageRank {
    var prev = initialInput(FileHelper.createTempCopyOf("urls.txt"))
    var delta = 1.0f
    while (delta > 0.01f) {
      prev = fastUpdate(prev, 0.5f)
      delta = prev.map((k, v) => math.abs(v._1 - v._2)).max.materialize.head
    }
    assertEquals(0.0048, delta, 0.001)
    pipeline.done
  }
}
