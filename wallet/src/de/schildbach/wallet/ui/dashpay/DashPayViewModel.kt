/*
 * Copyright 2020 Dash Core Group
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
package de.schildbach.wallet.ui.dashpay

import android.app.Application
import android.os.HandlerThread
import android.os.Process
import androidx.lifecycle.*
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.ui.security.SecurityGuard
import de.schildbach.wallet.ui.send.DeriveKeyTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.bitcoinj.crypto.KeyCrypterException
import org.bouncycastle.crypto.params.KeyParameter
import org.dashevo.dashpay.ContactRequests
import java.util.logging.Handler
import kotlin.coroutines.suspendCoroutine

class DashPayViewModel(application: Application) : AndroidViewModel(application) {

    private val platformRepo = PlatformRepo(application as WalletApplication)

    private val usernameLiveData = MutableLiveData<String>()
    private val userSearchLiveData = MutableLiveData<String>()

    // Job instance (https://stackoverflow.com/questions/57723714/how-to-cancel-a-running-livedata-coroutine-block/57726583#57726583)
    private var getUsernameJob = Job()
    private var searchUsernamesJob = Job()
    private val contactRequestJob by lazy { Job() }

    val getUsernameLiveData = Transformations.switchMap(usernameLiveData) { username ->
        getUsernameJob.cancel()
        getUsernameJob = Job()
        liveData(context = getUsernameJob + Dispatchers.IO) {
            if (username != null) {
                emit(Resource.loading(null))
                emit(platformRepo.getUsername(username))
            } else {
                emit(Resource.canceled())
            }
        }
    }

    fun searchUsername(username: String?) {
        usernameLiveData.value = username
    }

    override fun onCleared() {
        super.onCleared()
        getUsernameJob.cancel()
        searchUsernamesJob.cancel()
        contactRequestJob.cancel()
    }

    //
    // Search Usernames that start with "text".  Results are a list of documents for names
    // starting with text.  If no results are found then an empty list is returned.
    //
    val searchUsernamesLiveData = Transformations.switchMap(userSearchLiveData) { text: String ->
        searchUsernamesJob.cancel()
        searchUsernamesJob = Job()
        liveData(context = searchUsernamesJob + Dispatchers.IO) {
            emit(Resource.loading(null))
            emit(platformRepo.searchUsernames(text))
        }
    }

    fun searchUsernames(text: String) {
        userSearchLiveData.value = text
    }

    val isPlatformAvailableLiveData = liveData(Dispatchers.IO) {
        emit(Resource.loading(null))
        emit(platformRepo.isPlatformAvailable())
    }

    fun usernameDoneAndDismiss() {
        viewModelScope.launch {
            platformRepo.doneAndDismiss()
        }
    }

    fun sendContactRequest(toUserId: String) {
        val walletApplication = WalletApplication.getInstance()
        val backgroundThread = HandlerThread("background", Process.THREAD_PRIORITY_BACKGROUND)
        backgroundThread.start()
        val backgroundHandler = android.os.Handler(backgroundThread.looper)
        val securityGuard = SecurityGuard()
        val password = securityGuard.retrievePassword()
        object : DeriveKeyTask(backgroundHandler, walletApplication.scryptIterationsTarget()) {
            override fun onSuccess(encryptionKey: KeyParameter, wasChanged: Boolean) {
                GlobalScope.launch {
                    platformRepo.sendContactRequest(toUserId, encryptionKey)
                    println("contact request sent")
                }
            }

            override fun onFailure(ex: KeyCrypterException?) {
                println("failed to decrypt key")
                //CreateIdentityService.log.error("unable to decrypt wallet", ex)
            }
        }.deriveKey(walletApplication.wallet, password)
    }

}