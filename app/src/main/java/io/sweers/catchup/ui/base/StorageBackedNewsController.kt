/*
 * Copyright (c) 2017 Zac Sweers
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

package io.sweers.catchup.ui.base

import android.os.Bundle
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import io.sweers.catchup.data.CatchUpItem
import io.sweers.catchup.data.CatchUpItem2
import io.sweers.catchup.data.ServiceDao
import io.sweers.catchup.data.ServicePage
import org.threeten.bp.Instant
import org.threeten.bp.temporal.ChronoUnit
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

abstract class StorageBackedNewsController : BaseNewsController<CatchUpItem> {

  @Inject protected lateinit var dao: ServiceDao
  protected var currentSessionId: Long = -1

  constructor() : super()

  constructor(args: Bundle) : super(args)

  protected abstract fun getDataFromService(page: Int): Single<List<CatchUpItem2>>

  protected abstract fun serviceType(): String

  override fun getDataSingle(request: BaseNewsController.DataRequest): Single<List<CatchUpItem>> {
    if (request.multipage) {
      // Backfill pages
      return Observable.range(0, request.page)
          .flatMapSingle { this.getPage(it) }
          .collectInto(mutableListOf<CatchUpItem>()) { list, collection ->
            list.addAll(collection.map { CatchUpItem.from(it) })
          }
          .map { it } // Weird
    } else {
      return getPage(request.page, request.fromRefresh)
          .flattenAsObservable { it }
          .map { CatchUpItem.from(it) }
          .toList()
    }
  }

  private fun getPage(page: Int, isRefresh: Boolean = false): Single<List<CatchUpItem2>> {
    // If not refresh
    // If it's page 0, save session ID
    // If it's not page 0, use prev ID
    return if (!isRefresh) {
      fetchPageFromLocal(page)
          .switchIfEmpty(fetchPageFromNetwork(page, isRefresh).toMaybe())
          .toSingle()
    } else {
      fetchPageFromNetwork(page, isRefresh)
    }
  }

  private fun fetchPageFromLocal(page: Int,
      useLatest: Boolean = false): Maybe<List<CatchUpItem2>> {
    return with(dao) {
      if (page == 0 && useLatest) {
        getFirstServicePage(serviceType())
      } else if (page == 0) {
        getFirstServicePage(type = serviceType(),
            expiration = Instant.now())
      } else {
        getServicePage(type = serviceType(), page = page, sessionId = currentSessionId)
      }
    }
        .subscribeOn(Schedulers.io())
        .flatMap { servicePage ->
          if (page == 0) {
            currentSessionId = servicePage.sessionId
          }
          // We want to preserve the ordering that we stored before, map ID -> index
          val idToIndex = servicePage.items.withIndex()
              .associateBy(keySelector = { (_, value) -> value }) { (index, _) -> index }
          dao.getItemByIds(servicePage.items.toTypedArray())
              .flattenAsObservable { it }
              .toSortedList { o1, o2 ->
                idToIndex[o1.stableId()]!!.compareTo(idToIndex[o2.stableId()]!!)
              }
              .toMaybe()
        }
  }

  private fun fetchPageFromNetwork(page: Int, isRefresh: Boolean): Single<List<CatchUpItem2>> {
    return getDataFromService(page)
        .doOnSuccess { posts ->
          Completable.fromAction {
            val calculatedExpiration = Instant.now().plus(2,
                ChronoUnit.HOURS) // TODO preference this
            if (currentSessionId == -1L) {
              currentSessionId = calculatedExpiration.toEpochMilli()
            }
            dao.putPage(ServicePage().apply {
              id = "${serviceType()}$page"
              type = serviceType()
              ServicePage@ this.page = page
              items = posts.map { it.stableId() }
              expiration = calculatedExpiration
              sessionId = if (page == 0 && isRefresh) {
                calculatedExpiration.toEpochMilli()
              } else {
                currentSessionId
              }
            })
          }.subscribeOn(Schedulers.io())
              .blockingAwait()
        }
        .doOnSuccess { posts ->
          Completable.fromAction {
            dao.putItems(*posts.toTypedArray())
          }.subscribeOn(Schedulers.io())
              .blockingAwait()
        }
        .flattenAsObservable { it }
        .onErrorResumeNext { throwable: Throwable ->
          // At least *try* to gracefully handle it
          if (throwable is HttpException) {
            Observable.error(throwable)
          } else if (page == 0 && !isRefresh && throwable is IOException) {
            fetchPageFromLocal(page).flattenAsObservable { it }
          } else {
            Observable.error(throwable)
          }
        }
        .toList()
  }
}
