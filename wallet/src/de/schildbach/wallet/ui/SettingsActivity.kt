/*
 * Copyright 2019 Dash Core Group
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

package de.schildbach.wallet.ui

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_settings.*

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        val actionBar = supportActionBar
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        setTitle(R.string.settings_title)

        about.setOnClickListener { startAboutActivity() }
        local_currency.setOnClickListener { startExchangeRatesActivity() }
    }

    override fun onStart() {
        super.onStart()
        local_currency_symbol.text = WalletApplication.getInstance()
                .configuration.exchangeCurrencyCode
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.activity_stay, R.anim.slide_out_left)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun startAboutActivity() {
        startActivity(Intent(this, AboutActivity::class.java))
        overridePendingTransition(R.anim.slide_in_right, R.anim.activity_stay)
    }

    private fun startExchangeRatesActivity() {
        startActivity(Intent(this, ExchangeRatesActivity::class.java))
        overridePendingTransition(R.anim.slide_in_right, R.anim.activity_stay)
    }

}
