/*
Copyright 2014 Google Inc. All rights reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.google.cloud.genomics.spark.examples.rdd

import java.lang.{Double => JDouble}
import java.util.{List => JList}

import scala.collection.JavaConversions._

import org.apache.spark.Partition
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.TaskContext
import org.apache.spark.rdd.RDD

import com.google.api.services.genomics.Genomics
import com.google.api.services.genomics.model.{Call => CallModel}
import com.google.api.services.genomics.model.{Variant => VariantModel}
import com.google.cloud.genomics.Client

/**
 * A serializable version of the Variant.
 * Currently Java Client model objects are not serializable, see
 * https://code.google.com/p/google-api-java-client/issues/detail?id=390
 * for more information.
 */

case class Call(callsetId: String, callsetName: String, genotype: List[Integer], 
    genotypeLikelihood: Option[List[JDouble]], phaseset: String, 
    info: Map[String, JList[String]]) extends Serializable


case class Variant(contig: String, id: String, names: Option[List[String]], 
    position: Long, end: Option[Long], referenceBases: String, 
    alternateBases: Option[List[String]], info: Map[String, JList[String]], 
    created: Long, datasetId: String, calls: Option[Seq[Call]]) extends Serializable

object VariantBuilder {
  def fromJavaVariant(r: VariantModel) = {
    val variantKey = VariantKey(r.getContig, r.getPosition.toLong)

    val calls = if (r.containsKey("calls"))
        Some(r.getCalls().map(
            c => Call(
                c.getCallsetId, 
                c.getCallsetName, 
                c.getGenotype.toList,
                if (c.containsKey("genotypeLikelihood"))
                  Some(c.getGenotypeLikelihood.toList)
                else
                  None,
                c.getPhaseset,
                c.getInfo.toMap)))
      else
        None

    val variant = Variant(
        r.getContig, 
        r.getId, 
        if (r.containsKey("names"))
          Some(r.getNames.toList)
        else
          None,
        r.getPosition,
        if (r.containsKey("end")) 
          Some(r.getEnd)
        else 
          None, 
        r.getReferenceBases,
        if (r.containsKey("alternateBases")) 
          Some(r.getAlternateBases.toList) 
        else 
          None,
        r.getInfo.toMap, 
        if (r.containsKey("created"))
          r.getCreated
        else
          0L,
        r.getVariantsetId,
        calls)
    (variantKey, variant)
  }

  def toJavaVariant(r: Variant) = {
    val variant = new VariantModel()
    .setContig(r.contig)
    .setCreated(r.created)
    .setVariantsetId(r.datasetId)
    .setId(r.id)
    .setInfo(r.info)
    .setPosition(r.position)
    .setReferenceBases(r.referenceBases)

    if (r.alternateBases isDefined) variant.setAlternateBases(r.alternateBases.get)
    if (r.end isDefined) variant.setEnd(r.end.get.toLong)
    if (r.names isDefined) variant.setNames(r.names.get)
    if (r.calls isDefined) {
      val calls = r.calls.get.map
      { c =>
        val call = new CallModel()
        .setCallsetId(c.callsetId)
        .setCallsetName(c.callsetName)
        .setGenotype(c.genotype)
        .setInfo(c.info)
        .setPhaseset(c.phaseset)
        if (c.genotypeLikelihood isDefined) call.setGenotypeLikelihood(c.genotypeLikelihood.get)

        call
      }
      variant.setCalls(calls)
    }

    variant
  }
}

/**
 * A simple Spark RDD backed by Google Genomics VariantStore and
 * populated via the SearchVariants API call
 * (https://developers.google.com/genomics/v1beta/reference/variants/search).
 */
class VariantsRDD(sc: SparkContext,
    applicationName: String,
    clientSecretsFile: String,
    dataset: String,
    variantsPartitioner: VariantsPartitioner)
    extends RDD[(VariantKey, Variant)](sc, Nil) {

  override val partitioner = Some(variantsPartitioner)

  override def getPartitions: Array[Partition] = {
    variantsPartitioner.getPartitions(dataset)
  }

  override def compute(part: Partition, ctx: TaskContext):
  Iterator[(VariantKey, Variant)] = {
    new VariantsIterator(Client(applicationName,
            clientSecretsFile).genomics, part.asInstanceOf[VariantsPartition])
  }
}

/**
 * Defines a search range over a contig.
 */
case class VariantsPartition(override val index: Int,
                          val dataset: String,
                          val contig: String,
                          val start: Long,
                          val end: Long) extends Partition

/**
 * Indexes a variant to its partition.
 */
case class VariantKey(contig: String, position: Long)
