/*
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

package org.apache.gearpump.streaming.dsl

import com.typesafe.config.Config
import org.apache.gearpump.cluster.UserConfig
import org.apache.gearpump.experiments.hbase.{HBaseConsumer, HBaseRepo, HBaseSink, HBaseSinkInterface}
import org.apache.gearpump.streaming.dsl.op.OpType._
import org.apache.gearpump.streaming.dsl.op._
import org.apache.gearpump.streaming.task.{Task, TaskContext}
import org.apache.gearpump.util.{Graph, LogUtil}
import org.apache.hadoop.conf.Configuration
import org.slf4j.{Logger, LoggerFactory}

import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

class Stream[T:ClassTag](private val graph: Graph[Op,OpEdge], private val thisNode:Op, private val edge: Option[OpEdge] = None) {

  /**
   * convert a value[T] to a list of value[R]
   * @param fun function
   * @param description the descripton message for this opeartion
   * @tparam R return type
   * @return
   */
  def flatMap[R: ClassTag](fun: T => TraversableOnce[R], description: String = null): Stream[R] = {
    val flatMapOp = FlatMapOp(fun, Option(description).getOrElse("flatmap"))
    graph.addVertex(flatMapOp )
    graph.addEdge(thisNode, edge.getOrElse(Direct), flatMapOp)
    new Stream[R](graph, flatMapOp)
  }

  /**
   * convert value[T] to value[R]
   * @param fun function
   * @tparam R return type
   * @return
   */
  def map[R: ClassTag](fun: T => R, description: String = null): Stream[R] = {
    this.flatMap ({ data =>
      Option(fun(data))
    }, Option(description).getOrElse("map"))
  }

  /**
   * reserve records when fun(T) == true
   * @param fun
   * @return
   */
  def filter(fun: T => Boolean, description: String = null): Stream[T] = {
    this.flatMap ({ data =>
      if (fun(data)) Option(data) else None
    }, Option(description).getOrElse("filter"))
  }

  /**
   * Reduce opeartion
   * @param fun
   * @param description description message for this operator
   * @return
   */
  def reduce(fun: (T, T) => T, description: String = null): Stream[T] = {
    val reduceOp = ReduceOp(fun, Option(description).getOrElse("reduce"))
    graph.addVertex(reduceOp)
    graph.addEdge(thisNode, edge.getOrElse(Direct), reduceOp)
    new Stream(graph, reduceOp)
  }

  /**
   * Log to task log file
   */
  def log(): Unit = {
    this.map(msg => LoggerFactory.getLogger("dsl").info(msg.toString), "log")
  }

  /**
   * Merge data from two stream into one
   * @param other
   * @return
   */
  def merge(other: Stream[T], description: String = null): Stream[T] = {
    val mergeOp = MergeOp(thisNode, other.thisNode, Option(description).getOrElse("merge"))
    graph.addVertex(mergeOp)
    graph.addEdge(thisNode, edge.getOrElse(Direct), mergeOp)
    graph.addEdge(other.thisNode, other.edge.getOrElse(Shuffle), mergeOp)
    new Stream[T](graph, mergeOp)
  }

  /**
   * Group by fun(T)
   *
   * For example, we have T type, People(name: String, gender: String, age: Int)
   * groupBy[People](_.gender) will group the people by gender.
   *
   * You can append other combinators after groupBy
   *
   * For example,
   *
   * Stream[People].groupBy(_.gender).flatmap(..).filter.(..).reduce(..)
   *
   * @param fun
   * @param parallism
   * @tparam Group
   * @return
   */
  def groupBy[Group](fun: T => Group, parallism: Int = 1, description: String = null): Stream[T] = {
    val groupOp = GroupByOp(fun, parallism, Option(description).getOrElse("groupBy"))
    graph.addVertex(groupOp)
    graph.addEdge(thisNode, edge.getOrElse(Shuffle), groupOp)
    new Stream[T](graph, groupOp)
  }

  /**
   * connect with a low level Processor(TaskDescription)
   * @param processor
   * @param parallism
   * @tparam R
   * @return
   */
  def process[R: ClassTag](processor: Class[_ <: Task], parallism: Int, description: String = null): Stream[R] = {
    val processorOp = ProcessorOp(processor, parallism, Option(description).getOrElse("process"))
    graph.addVertex(processorOp)
    graph.addEdge(thisNode, edge.getOrElse(Shuffle), processorOp)
    new Stream[R](graph, processorOp, Some(Shuffle))
  }

}

class KVStream[K, V](stream: Stream[Tuple2[K, V]]){
  /**
   * Apply to Stream[Tuple2[K,V]]
   * Group by the key of a KV tuple
   * For (key, value) will groupby key
   * @return
   */
  def groupByKey(parallism: Int = 1): Stream[Tuple2[K, V]] = {
    stream.groupBy(Stream.getTupleKey[K, V], parallism, "groupByKey")
  }


  /**
   * Sum the value of the tuples
   *
   * Apply to Stream[Tuple2[K,V]], V must be of type Number
   *
   * For input (key, value1), (key, value2), will generate (key, value1 + value2)
   *
   * @return
   */
  def sum(implicit numeric: Numeric[V]) = {
    stream.reduce(Stream.sumByValue[K, V](numeric), "sum")
  }
}

object Stream {

  def apply[T: ClassTag](graph: Graph[Op, OpEdge], node: Op, edge: Option[OpEdge]) = new Stream[T](graph, node, edge)

  def getTupleKey[K, V](tuple: Tuple2[K, V]): K = tuple._1

  def sumByValue[K, V](numeric: Numeric[V]): (Tuple2[K, V], Tuple2[K, V]) => Tuple2[K, V]
  = (tuple1, tuple2) => Tuple2(tuple1._1, numeric.plus(tuple1._2, tuple2._2))

  implicit def streamToKVStream[K, V](stream: Stream[Tuple2[K, V]]): KVStream[K, V] = new KVStream(stream)

  implicit class Sink[T: ClassTag](stream: Stream[T]) extends java.io.Serializable {
    def sink[M[_] <: SinkConsumer[_], T: ClassTag](sinkConsumer: M[T], parallism: Int, description: String = null): Stream[T] = {
      implicit val sink = TraversableSink(sinkConsumer, parallism, Some(description).getOrElse("traversable"))
      stream.graph.addVertex(sink)
      stream.graph.addEdge(stream.thisNode, Shuffle, sink)
      new Stream[T](stream.graph, sink)
    }

    def writeToSink(config: Config, sinkClosure: SinkClosure[T], parallelism: Int = 1, description: String = null): Stream[T] = {
      this.sink(new SinkConsumer(config, sinkClosure), parallelism, description)
    }

    def writeToHBase(config: Config, sinkClosure: SinkClosure[T], parallelism: Int = 1, description: String = null): Stream[T] = {
      this.sink(new HBaseSinkConsumer(config, sinkClosure), parallelism, description)
    }
  }
}

class SinkConsumer[T:ClassTag](config: Config, sinkClosure: SinkClosure[T]) extends java.io.Serializable {
  val LOG: Logger = LogUtil.getLogger(getClass)
  def process(taskContext: TaskContext, userConfig: UserConfig): T => Unit = {
    Try({
      sinkClosure(null, null)
    }) match {
      case Success(success) =>
        success
      case Failure(ex) =>
        LOG.error("Failed to call sink closure", ex)
        dummy: T => {}
    }
  }
}

class HBaseSinkConsumer[T: ClassTag](config: Config, sinkClosure: SinkClosure[T]) extends SinkConsumer[T](config, sinkClosure) {
  val repo = new HBaseRepo {
    def getHBase(table: String, conf: Configuration): HBaseSinkInterface = HBaseSink(table, conf)
  }
  override def process(taskContext: TaskContext, userConfig: UserConfig): T => Unit = {
    Try({
      val hbaseConsumer = HBaseConsumer(taskContext.system, Some(config))
      val hbase = hbaseConsumer.getHBase(repo)
      sinkClosure(hbase, hbaseConsumer)
    }) match {
      case Success(success) =>
        success
      case Failure(ex) =>
        LOG.error("Failed to call sink closure", ex)
        dummy: T => {}
    }
  }
}

