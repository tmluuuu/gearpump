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

package org.apache.gearpump.streaming

import java.util.concurrent.TimeUnit

import akka.actor._
import akka.pattern.{ask, pipe}
import akka.remote.RemoteScope
import org.apache.gearpump._
import org.apache.gearpump.cluster.AppMasterToMaster._
import org.apache.gearpump.cluster.AppMasterToWorker._
import org.apache.gearpump.cluster.MasterToAppMaster._
import org.apache.gearpump.cluster.WorkerToAppMaster._
import org.apache.gearpump.cluster._
import org.apache.gearpump.cluster.scheduler.{Resource, ResourceRequest}
import org.apache.gearpump.streaming.AppMasterToExecutor.{LaunchTask, RecoverTasks, RestartTasks}
import org.apache.gearpump.streaming.ConfigsHelper._
import org.apache.gearpump.streaming.ExecutorToAppMaster._
import org.apache.gearpump.streaming.task._
import org.apache.gearpump.transport.HostPort
import org.apache.gearpump.util.ActorSystemBooter.{BindLifeCycle, RegisterActorSystem}
import org.apache.gearpump.util._
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable
import scala.collection.mutable.Queue
import scala.concurrent.Future
import scala.concurrent.duration.{Duration, FiniteDuration}

class AppMaster (config : Configs) extends Actor {

  import org.apache.gearpump.streaming.AppMaster._

  val masterExecutorId = config.executorId
  var currentExecutorId = masterExecutorId + 1
  val resource = config.resource

  import context.dispatcher

  private val appId = config.appId
  private val appDescription = config.appDescription.asInstanceOf[AppDescription]
  private val masterProxy = config.masterProxy
  private var master : ActorRef = null

  private val registerData = config.appMasterRegisterData

  private val name = appDescription.name
  private var taskMap = Map.empty[TaskId, TaskLaunchData]
  private var taskQueues = Map.empty[Locality, Queue[TaskLaunchData]]
  private val taskLocator = new TaskLocator(config)

  private var clockService : ActorRef = null
  private var startClock : TimeStamp = 0L

  private var taskLocations = Map.empty[HostPort, Set[TaskId]]
  private var executorIdToTasks = Map.empty[Int, Set[TaskId]]

  private var startedTasks = Set.empty[TaskId]
  private var totalTaskCount = 0

  override def receive : Receive = null

  override def preStart: Unit = {
    LOG.info(s"AppMaster[$appId] is launched $appDescription")
    val dag = DAG(appDescription.dag)

    initTaskQueues(dag)

    LOG.info("AppMaster is launched xxxxxxxxxxxxxxxxx")

    LOG.info("Initializing Clock service ....")
    clockService = context.actorOf(Props(classOf[ClockService], dag))

    context.become(waitForMasterToConfirmRegistration(repeatActionUtil(30)(masterProxy ! RegisterAppMaster(self, appId, masterExecutorId, resource, registerData))))
  }

  def waitForMasterToConfirmRegistration(killSelf : Cancellable) : Receive = {
    case AppMasterRegistered(appId, master) =>
      LOG.info(s"AppMasterRegistered received for appID: $appId")

      LOG.info("Sending request resource to master...")
      taskQueues.foreach(params => {
        val (locality, tasks) = params
        locality match {
          case locality: WorkerLocality => master ! RequestResource(appId, ResourceRequest(Resource(tasks.size), locality.workerId))
          case _ => master ! RequestResource(appId, ResourceRequest(Resource(tasks.size)))
        }
      })

      killSelf.cancel()
      this.master = master
      context.watch(master)
      context.become(messageHandler)
  }

  def messageHandler: Receive = masterMsgHandler orElse selfMsgHandler orElse appManagerMsgHandler orElse workerMsgHandler orElse clientMsgHandler orElse executorMsgHandler orElse terminationWatch


  def masterMsgHandler: Receive = {
    case ResourceAllocated(allocations) => {
      LOG.info(s"AppMaster $appId received ResourceAllocated $allocations")
      //group resource by worker
      val actorToWorkerId = mutable.HashMap.empty[ActorRef, Int]
      val groupedResource = allocations.groupBy(_.worker).mapValues(_.foldLeft(Resource.empty)((totalResource, request) => totalResource add request.resource)).toArray
      allocations.foreach(allocation => actorToWorkerId.put(allocation.worker, allocation.workerId))

      groupedResource.map((workerAndResources) => {
        val (worker, resource) = workerAndResources
        LOG.info(s"Launching Executor ...appId: $appId, executorId: $currentExecutorId, slots: ${resource.slots} on worker $worker")
        val executorConfig = appDescription.conf.withAppId(appId).withAppMaster(self).withExecutorId(currentExecutorId).withResource(resource).withStartTime(startClock).withWorkerId(actorToWorkerId.get(worker).get)
        context.actorOf(Props(classOf[ExecutorLauncher], worker, appId, currentExecutorId, resource, executorConfig))
        currentExecutorId += 1
      })
    }
  }

  def appManagerMsgHandler: Receive = {
    case appMasterDataDetailRequest: AppMasterDataDetailRequest =>
      val appId = appMasterDataDetailRequest.appId
      LOG.info(s"Received AppMasterDataDetailRequest $appId")
      appId match {
        case this.appId =>
          LOG.info(s"Sending back AppMasterDataDetailRequest $appId")
          sender ! AppMasterDataDetail(appId = appId, appDescription = appDescription)
      }
  }

  def executorMsgHandler: Receive = {
    case RegisterTask(taskId, executorId, host) => {
      LOG.info(s"Task $taskId has been Launched for app $appId")

      var taskIds = taskLocations.get(host).getOrElse(Set.empty[TaskId])
      taskIds += taskId
      taskLocations += host -> taskIds

      var taskSetForExecutorId = executorIdToTasks.get(executorId).getOrElse(Set.empty[TaskId])
      taskSetForExecutorId += taskId
      executorIdToTasks += executorId -> taskSetForExecutorId

      startedTasks += taskId

      LOG.info(s" started task size: ${startedTasks.size}, taskQueue size: ${totalTaskCount}")
      if (startedTasks.size == totalTaskCount) {
        context.children.foreach { executor =>
          LOG.info(s"Sending Task locations to executor ${executor.path.name}")
          executor ! TaskLocations(taskLocations)
        }
      }
    }

    case clock : UpdateClock =>
      clockService forward clock
    case GetLatestMinClock =>
      clockService forward GetLatestMinClock

    case task: TaskFinished => {
      val taskId = task.taskId
      LOG.info(s"Task ${taskId} has been finished for app $appId")

      taskLocations.keys.foreach { host =>
        if (taskLocations.contains(host)) {
          var set = taskLocations.get(host).get
          if (set.contains(taskId)) {
            set -= taskId
            taskLocations += host -> set
          }
        }
      }

      task match {
        case TaskSuccess(taskId) => Unit
        case TaskFailed(taskId, reason, ex) =>
          LOG.info(s"Task failed, taskId: $taskId for app $appId")
          //TODO: Reschedule the task to other nodes
      }
    }
  }

  def selfMsgHandler : Receive = {
    case LaunchExecutorActor(conf : Props, executorId : Int, daemon : ActorRef) =>
      val executor = context.actorOf(conf, executorId.toString)
      daemon ! BindLifeCycle(executor)
  }

  def workerMsgHandler : Receive = {
    case RegisterExecutor(executor, executorId, resource, workerId) => {
      LOG.info(s"executor $executorId has been launched")
      //watch for executor termination
      context.watch(executor)
      val taskLocality = WorkerLocality(workerId)
      var queue = taskQueues.getOrElse(taskLocality, Queue.empty[TaskLaunchData])

      def launchTask(remainResources: Resource): Unit = {
        if (remainResources.greaterThan(Resource.empty) && !queue.isEmpty) {
          val TaskLaunchData(taskId, taskDescription, dag) = queue.dequeue()
          //Launch task

          LOG.info("Sending Launch Task to executor: " + executor.toString())

          val executorByPath = context.actorSelection("../app_0_executor_0")

          val config = appDescription.conf.withAppId(appId).withExecutorId(executorId).withAppMaster(self).withDag(dag)
          executor ! LaunchTask(taskId, config, taskDescription.taskClass)
          //Todo: subtract the actual resource used by task
          val usedResource = Resource(1)
          launchTask(remainResources subtract usedResource)
        } else if(remainResources.greaterThan(Resource.empty) && queue.isEmpty) {
          queue = taskQueues.getOrElse(NonLocality, Queue.empty[TaskLaunchData])
          if(queue.nonEmpty) {
            launchTask(remainResources)
          }
        }
      }
      launchTask(resource)
    }
    case ExecutorLaunchRejected(reason, ex) => {
      LOG.error(s"Executor Launch failed reason：$reason", ex)
    }
  }

  implicit val timeout = akka.util.Timeout(3, TimeUnit.SECONDS)
  def clientMsgHandler : Receive = {
    case RecoverTasks(minClock, tasks) =>
      LOG.info(s"Restarting to clock $minClock...")

      //clean the state
      startClock = minClock
      taskLocations = taskLocations.empty
      startedTasks = startedTasks.empty

      //allocate resource for failed tasks
      val taskQueue = taskQueues.getOrElse(NonLocality, Queue.empty[TaskLaunchData])
      if (null != tasks) {
        tasks.foreach { taskId =>
          val task = taskMap(taskId)
          taskQueue.enqueue(task)
        }
        master ! RequestResource(appId, ResourceRequest(Resource(taskQueue.size)))
      }

      //restart existing tasks
      context.children.foreach(_ ! RestartTasks(startClock))
  }

  //TODO: We an task is down, we need to recover
  def terminationWatch : Receive = {
    case Terminated(actor) =>
    if (null != master && actor.compareTo(master) == 0) {
      // master is down, let's try to contact new master
      LOG.info("parent master cannot be contacted, find a new master ...")
      context.become(waitForMasterToConfirmRegistration(repeatActionUtil(30)(masterProxy ! RegisterAppMaster(self, appId, masterExecutorId, resource, registerData))))
    } else if (ActorUtil.isChildActorPath(self, actor)) {
      //executor is down
      //TODO: handle this failure

      val executorId = actor.path.name.toInt
      LOG.error(s"Executor is down ${actor.path.name}, executorId: $executorId")

      val taskSet = executorIdToTasks(executorId)

      (clockService ? GetLatestMinClock).asInstanceOf[Future[LatestMinClock]].map(clock => RecoverTasks(clock.clock, taskSet)).pipeTo(self)
    } else {
      LOG.error(s"=============terminiated unknown actorss===============${actor.path}")
    }
  }

  import context.dispatcher

  private def repeatActionUtil(seconds: Int)(action : => Unit) : Cancellable = {

    val cancelSend = context.system.scheduler.schedule(Duration.Zero, Duration(2, TimeUnit.SECONDS))(action)
    val cancelSuicide = context.system.scheduler.scheduleOnce(FiniteDuration(seconds, TimeUnit.SECONDS), self, PoisonPill)
    return new Cancellable {
      def cancel(): Boolean = {
        val result1 = cancelSend.cancel()
        val result2 = cancelSuicide.cancel()
        result1 && result2
      }

      def isCancelled: Boolean = {
        cancelSend.isCancelled && cancelSuicide.isCancelled
      }
    }
  }

  private def initTaskQueues(dag : DAG) = {
    dag.tasks.foreach { params =>
      val (taskGroupId, taskDescription) = params
      0.until(taskDescription.parallism).map((taskIndex: Int) => {
        val taskId = TaskId(taskGroupId, taskIndex)
        val locality = taskLocator.locateTask(taskDescription)
        val taskLaunchData = TaskLaunchData(taskId, taskDescription, dag.subGraph(taskGroupId))
        taskMap += (taskId -> taskLaunchData)
        val taskQueue = taskQueues.getOrElse(locality, Queue.empty[TaskLaunchData])
        taskQueue.enqueue(taskLaunchData)
        taskQueues += (locality -> taskQueue)
        totalTaskCount += 1
      })
    }
    val nonLocalityQueue = taskQueues.getOrElse(NonLocality, Queue.empty[TaskLaunchData])
    //reorder the tasks to distributing fairly on workers
    val taskQueue = nonLocalityQueue.sortBy(_.taskId.index)
    taskQueues += (NonLocality -> taskQueue)
  }
}

object AppMaster {
  private val LOG: Logger = LoggerFactory.getLogger(classOf[AppMaster])

  case class TaskLaunchData(taskId: TaskId, taskDescription : TaskDescription, dag : DAG)

  class ExecutorLauncher (worker : ActorRef, appId : Int, executorId : Int, resource : Resource, executorConfig : Configs) extends Actor {

    private def actorNameForExecutor(appId : Int, executorId : Int) = "app" + appId + "-executor" + executorId

    val name = actorNameForExecutor(appId, executorId)
    val selfPath = ActorUtil.getFullPath(context)

    val launch = ExecutorContext(Util.getCurrentClassPath, context.system.settings.config.getString(Constants.GEARPUMP_EXECUTOR_ARGS).split(" "), classOf[ActorSystemBooter].getName, Array(name, selfPath))

    worker ! LaunchExecutor(appId, executorId, resource, launch)

    def receive : Receive = waitForActorSystemToStart


    def waitForActorSystemToStart : Receive = {
      case RegisterActorSystem(systemPath) =>
        LOG.info(s"Received RegisterActorSystem $systemPath for app master")
        val executorProps = Props(classOf[Executor], executorConfig).withDeploy(Deploy(scope = RemoteScope(AddressFromURIString(systemPath))))
        sender ! BindLifeCycle(worker)
        context.parent ! LaunchExecutorActor(executorProps, executorConfig.executorId, sender)
        context.stop(self)
    }
  }

  case class LaunchExecutorActor(executorConfig : Props, executorId : Int, daemon: ActorRef)
}
