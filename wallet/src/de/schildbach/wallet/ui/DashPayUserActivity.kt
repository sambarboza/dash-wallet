/*
 * Copyright 2020 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.ViewModelProvider
import com.bumptech.glide.Glide
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.ui.dashpay.DashPayViewModel
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_dashpay_user.*
import org.dash.wallet.common.InteractionAwareActivity

class DashPayUserActivity : InteractionAwareActivity() {

    private lateinit var dashPayViewModel: DashPayViewModel
    private val username by lazy { intent.getStringExtra(USERNAME) }
    private val profile: DashPayProfile by lazy { intent.getParcelableExtra(PROFILE) as DashPayProfile }
    private val displayName by lazy { profile?.displayName }

    companion object {
        private const val USERNAME = "username"
        private const val PROFILE = "profile"
        private const val CONTACT_REQUEST_SENT = "contact_request_sent"
        private const val CONTACT_REQUEST_RECEIVED = "contact_request_received"

        @JvmStatic
        fun createIntent(context: Context, username: String, profile: DashPayProfile?,
                         contactRequestSent: Boolean, contactRequestReceived: Boolean): Intent {
            val intent = Intent(context, DashPayUserActivity::class.java)
            intent.putExtra(USERNAME, username)
            intent.putExtra(PROFILE, profile)
            intent.putExtra(CONTACT_REQUEST_SENT, contactRequestSent)
            intent.putExtra(CONTACT_REQUEST_RECEIVED, contactRequestReceived)
            return intent
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashpay_user)

        close.setOnClickListener { finish() }

        val defaultAvatar = UserAvatarPlaceholderDrawable.getDrawable(this, username[0])
        if (profile != null) {
            Glide.with(this).load(profile!!.avatarUrl).circleCrop()
                    .placeholder(defaultAvatar).into(avatar)
        } else {
            avatar.background = defaultAvatar
        }
        if (displayName != null) {
            displayNameTxt.text = displayName
            usernameTxt.text = username
        } else {
            displayNameTxt.text = username
        }
        updateContactRelationUi()

        dashPayViewModel = ViewModelProvider(this).get(DashPayViewModel::class.java)
        dashPayViewModel.sendContactRequest(profile.userId)
    }

    private fun updateContactRelationUi() {
        val contactRequestSent = intent.getBooleanExtra(CONTACT_REQUEST_SENT, false)
        val contactRequestReceived = intent.getBooleanExtra(CONTACT_REQUEST_RECEIVED, false)

        listOf<View>(sendContactRequestBtn, payContactBtn, contactRequestSentTxt,
                contactRequestReceivedContainer).forEach { it.visibility = View.GONE }

        when (contactRequestSent to contactRequestReceived) {
            //No Relationship
            false to false -> {
                sendContactRequestBtn.visibility = View.VISIBLE
            }
            //Contact Established
            true to true -> {
                payContactBtn.visibility = View.VISIBLE
            }
            //Request Sent / Pending
            true to false -> {
                contactRequestSentTxt.visibility = View.VISIBLE
            }
            //Request Received
            false to true -> {
                payContactBtn.visibility = View.VISIBLE
                contactRequestReceivedContainer.visibility = View.VISIBLE
                requestTitle.text = getString(R.string.contact_request_received_title, username)
            }
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.activity_stay, R.anim.slide_out_bottom)
    }

}