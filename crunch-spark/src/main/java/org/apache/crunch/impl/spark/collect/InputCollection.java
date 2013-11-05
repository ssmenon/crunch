/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.crunch.impl.spark.collect;

import org.apache.crunch.Source;
import org.apache.crunch.SourceTarget;
import org.apache.crunch.impl.dist.DistributedPipeline;
import org.apache.crunch.impl.dist.collect.BaseInputCollection;
import org.apache.crunch.impl.mr.run.CrunchInputFormat;
import org.apache.crunch.impl.spark.SparkCollection;
import org.apache.crunch.impl.spark.SparkRuntime;
import org.apache.crunch.impl.spark.fn.InputConverterFunction;
import org.apache.crunch.impl.spark.fn.MapFunction;
import org.apache.crunch.io.impl.FileSourceImpl;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDDLike;

import java.io.IOException;

public class InputCollection<S> extends BaseInputCollection<S> implements SparkCollection {

  InputCollection(Source<S> source, DistributedPipeline pipeline) {
    super(source, pipeline);
  }

  public JavaRDDLike<?, ?> getJavaRDDLike(SparkRuntime runtime) {
    try {
      Job job = new Job(runtime.getConfiguration());
      FileInputFormat.addInputPaths(job, "/tmp"); //placeholder
      source.configureSource(job, -1);
      JavaPairRDD<?, ?> input = runtime.getSparkContext().newAPIHadoopRDD(
          job.getConfiguration(),
          CrunchInputFormat.class,
          source.getConverter().getKeyClass(),
          source.getConverter().getValueClass());
      input.rdd().setName(source.toString());
      return input
          .map(new InputConverterFunction(source.getConverter()))
          .map(new MapFunction(source.getType().getInputMapFn(), runtime.getRuntimeContext()));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}