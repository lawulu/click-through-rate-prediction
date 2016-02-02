/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.examples.kaggle

import scala.collection.mutable.ArrayBuffer

import org.apache.spark.ml.classification.RandomForestClassifier
import org.apache.spark.ml.evaluation.BinaryClassificationEvaluator
import org.apache.spark.ml.feature.{OneHotEncoder, StringIndexer, VectorAssembler}
import org.apache.spark.ml.tuning.{CrossValidator, ParamGridBuilder}
import org.apache.spark.ml.{Pipeline, PipelineStage}
import org.apache.spark.sql.{SQLContext, SaveMode}
import org.apache.spark.{SparkConf, SparkContext}

object ClickThroughRatePrediction {

  val categoricalColumns = Array(
    "banner_pos", "site_category", "app_category", "device_type",
    "C1", "C14", "C15", "C16", "C17",
    "C18", "C19", "C20", "C21"
  )

  def main(args: Array[String]): Unit = {
    val conf = new SparkConf().setAppName(this.getClass.getSimpleName)
    val sc = new SparkContext(conf)
    val sqlContext = new SQLContext(sc)
  }

  def run(sc: SparkContext, sqlContext: SQLContext, trainPath: String, testPath: String): Unit = {
    import sqlContext.implicits._

    // Loads training data and testing data
    val train = sqlContext.read
      .format("com.databricks.spark.csv")
      .option("header", "true")
      .option("inferSchema", "true")
      .load(trainPath).cache()
    val test = sqlContext.read
      .format("com.databricks.spark.csv")
      .option("header", "true")
      .option("inferSchema", "true")
      .load(testPath).cache()

    def getIndexedColumn(clm: String): String = s"${clm}_indexed"
    def getColumnVec(clm: String): String = s"${clm}_vec"

    // Formats data
    val stages1 = ArrayBuffer.empty[PipelineStage]
    val strIdxrClick = new StringIndexer()
      .setInputCol("click")
      .setOutputCol("label")
    stages1.append(strIdxrClick)
    categoricalColumns.foreach { clm =>
      val stringIndexer = new StringIndexer()
        .setInputCol(clm)
        .setOutputCol(getIndexedColumn(clm))
        .setHandleInvalid("skip")
      val oneHotEncoder = new OneHotEncoder()
        .setInputCol(getIndexedColumn(clm))
        .setOutputCol(getColumnVec(clm))
      Array(stringIndexer, oneHotEncoder)
      stages1.append(stringIndexer)
      stages1.append(oneHotEncoder)
    }
    val va = new VectorAssembler()
      .setInputCols(categoricalColumns.map(getColumnVec))
      .setOutputCol("features")
    stages1.append(va)
    val pipeline1 = new Pipeline().setStages(stages1.toArray)
    val model1 = pipeline1.fit(train)
    val trainDF = model1.transform(train).select($"label", $"features")
    val testDF = model1.transform(test).select($"id", $"features")

    // Trains a model
    val rf = new RandomForestClassifier()
    val pipeline = new Pipeline().setStages(Array(rf))
    val paramGrid = new ParamGridBuilder()
      .addGrid(rf.impurity, Array("entropy", "gini"))
      .addGrid(rf.labelCol, Array("label"))
      .addGrid(rf.featuresCol, Array("features"))
      .build()
    val cv = new CrossValidator()
      .setEstimator(pipeline)
      .setEvaluator(new BinaryClassificationEvaluator())
      .setEstimatorParamMaps(paramGrid)
      .setNumFolds(3)
    val model2 = cv.fit(trainDF)

    // Predicts with the trained model
    val result = model2.transform(testDF)
    result.write.mode(SaveMode.Overwrite).parquet("./result/")
  }
}
