package com.netflix.monix.grpc.runtime.utils

import monix.eval.Task
import monix.reactive.Observable
import monix.execution.Scheduler

object ObservableOps {
  def deferAction[T](action: Scheduler => Observable[T]): Observable[T] = {
    Observable.fromTask {
      Task.deferAction { scheduler => Task(action(scheduler)) }
    }.flatten
  }
}
