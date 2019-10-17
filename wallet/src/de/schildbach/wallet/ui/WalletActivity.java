/*
 * Copyright 2011-2015 the original author or authors.
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

package de.schildbach.wallet.ui;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.telephony.TelephonyManager;
import android.view.ContextMenu;
import android.view.Display;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.constraintlayout.motion.widget.MotionLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProviders;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.navigation.NavigationView;
import com.google.common.collect.ImmutableList;
import com.squareup.okhttp.HttpUrl;

import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.core.VersionedChecksummedBytes;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.wallet.Wallet;
import org.dash.wallet.common.Configuration;
import org.dash.wallet.common.ui.DialogBuilder;
import org.dash.wallet.integration.uphold.data.UpholdClient;
import org.dash.wallet.integration.uphold.ui.UpholdAccountActivity;
import org.dash.wallet.integration.uphold.ui.UpholdSplashActivity;

import java.io.IOException;
import java.util.Currency;
import java.util.Locale;

import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.WalletBalanceWidgetProvider;
import de.schildbach.wallet.data.PaymentIntent;
import de.schildbach.wallet.data.WalletLock;
import de.schildbach.wallet.ui.InputParser.BinaryInputParser;
import de.schildbach.wallet.ui.InputParser.StringInputParser;
import de.schildbach.wallet.ui.preference.PreferenceActivity;
import de.schildbach.wallet.ui.send.SendCoinsActivity;
import de.schildbach.wallet.ui.send.SweepWalletActivity;
import de.schildbach.wallet.ui.widget.UpgradeWalletDisclaimerDialog;
import de.schildbach.wallet.util.CrashReporter;
import de.schildbach.wallet.util.FingerprintHelper;
import de.schildbach.wallet.util.Nfc;
import de.schildbach.wallet_test.R;
import kotlin.Pair;

/**
 * @author Andreas Schildbach
 */
public final class WalletActivity extends AbstractBindServiceActivity
        implements ActivityCompat.OnRequestPermissionsResultCallback,
        NavigationView.OnNavigationItemSelectedListener,
        UpgradeWalletDisclaimerDialog.OnUpgradeConfirmedListener,
        EncryptNewKeyChainDialogFragment.OnNewKeyChainEncryptedListener,
        WalletTransactionsFragment.MotionLayoutProvider {

    private static final int DIALOG_BACKUP_WALLET_PERMISSION = 0;
    private static final int DIALOG_RESTORE_WALLET_PERMISSION = 1;
    private static final int DIALOG_RESTORE_WALLET = 2;
    private static final int DIALOG_TIMESKEW_ALERT = 3;
    private static final int DIALOG_VERSION_ALERT = 4;
    private static final int DIALOG_LOW_STORAGE_ALERT = 5;

    private WalletApplication application;
    private Configuration config;
    private Wallet wallet;
    private FingerprintHelper fingerprintHelper;

    private DrawerLayout viewDrawer;
    private View viewFakeForSafetySubmenu;

    private Handler handler = new Handler();

    private static final int REQUEST_CODE_SCAN = 0;
    private static final int REQUEST_CODE_BACKUP_WALLET = 1;
    private static final int REQUEST_CODE_RESTORE_WALLET = 2;

    private boolean isRestoringBackup;

    private ClipboardManager clipboardManager;

    private boolean showBackupWalletDialog = false;

    private CheckPinSharedModel checkPinSharedModel;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        application = getWalletApplication();
        config = application.getConfiguration();
        wallet = application.getWallet();

        setContentViewFooter(R.layout.home_activity);
        setSupportActionBar((Toolbar) findViewById(R.id.toolbar));
        adjustHeaderLayout();
        activateHomeButton();

        if (savedInstanceState == null) {
            checkAlerts();
        }

        config.touchLastUsed();

        handleIntent(getIntent());

        MaybeMaintenanceFragment.add(getSupportFragmentManager());

        initUphold();
        initView();
        initViewModel();

        //Prevent showing dialog twice or more when activity is recreated (e.g: rotating device, etc)
        if (savedInstanceState == null) {
            //Add BIP44 support and PIN if missing
            upgradeWalletKeyChains(Constants.BIP44_PATH, false);
        }

        initFingerprintHelper();

        this.clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

        if (config.remindBackupSeed() && config.lastDismissedReminderMoreThan24hAgo()) {
            BackupWalletToSeedDialogFragment.show(getSupportFragmentManager());
        }
    }

    private void initViewModel() {
        checkPinSharedModel = ViewModelProviders.of(this).get(CheckPinSharedModel.class);
        checkPinSharedModel.getOnCorrectPinCallback().observe(this, new Observer<Pair<Integer, String>>() {
            @Override
            public void onChanged(Pair<Integer, String> integerStringPair) {
                WalletTransactionsFragment walletTransactionsFragment = (WalletTransactionsFragment)
                        getSupportFragmentManager().findFragmentById(R.id.wallet_transactions_fragment);
                if (walletTransactionsFragment != null) {
                    walletTransactionsFragment.onLockChanged(WalletLock.getInstance().isWalletLocked(wallet));
                }
            }
        });
    }

    private void adjustHeaderLayout() {
        Display display = getWindowManager().getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        float screenHeight = size.y;
        int headerHeight = (int) (screenHeight * 0.5);
        ViewGroup.LayoutParams headerPaneParams = findViewById(R.id.header_pane).getLayoutParams();
        headerPaneParams.height = headerHeight;
        MotionLayout motionLayout = findViewById(R.id.home_content);
        ConstraintSet constraintSetExpanded = motionLayout.getConstraintSet(R.id.expanded);
        constraintSetExpanded.constrainHeight(R.id.header_pane, headerHeight);
        View availableBalanceView = findViewById(R.id.available_balance);
        MotionLayout.LayoutParams availableBalanceParams = (MotionLayout.LayoutParams) availableBalanceView.getLayoutParams();
        availableBalanceParams.bottomMargin = (int) ((float) headerHeight * 0.35f);
    }

    private void initFingerprintHelper() {
        //Init fingerprint helper
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            fingerprintHelper = new FingerprintHelper(this);
            if (!fingerprintHelper.init()) {
                fingerprintHelper = null;
            }
        }
    }

    private void initUphold() {
        //Uses Sha256 hash of excerpt of xpub as Uphold authentication salt
        String xpub = wallet.getWatchingKey().serializePubB58(Constants.NETWORK_PARAMETERS);
        byte[] xpubExcerptHash = Sha256Hash.hash(xpub.substring(4, 15).getBytes());
        String authenticationHash = Sha256Hash.wrap(xpubExcerptHash).toString();

        UpholdClient.init(getApplicationContext(), authenticationHash);
    }

    private void initView() {
        initNavigationDrawer();
        initQuickActions();
        findViewById(R.id.uphold_account_section).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startUpholdActivity();
                viewDrawer.closeDrawer(GravityCompat.START);
            }
        });
        findViewById(R.id.pay_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(PaymentsActivity.createIntent(WalletActivity.this, PaymentsActivity.ACTIVE_TAB_PAY));
            }
        });
        findViewById(R.id.receive_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(PaymentsActivity.createIntent(WalletActivity.this, PaymentsActivity.ACTIVE_TAB_RECEIVE));
            }
        });
    }

    private void initQuickActions() {
        showHideSecureAction();
        findViewById(R.id.secure_action).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (config.remindBackupSeed()) {
                    handleBackupWalletToSeed();
                } else if (Constants.SUPPORT_BOTH_BACKUP_WARNINGS && config.remindBackup()) {
                    handleBackupWallet();
                }
            }
        });
        findViewById(R.id.scan_to_pay_action).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handleScan();
            }
        });
        findViewById(R.id.buy_sell_action).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startUpholdActivity();
            }
        });
        findViewById(R.id.pay_to_address_action).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handlePaste();
            }
        });
    }

    private void showHideSecureAction() {
        View secureActionView = findViewById(R.id.secure_action);
        boolean showBackupDisclaimer = (config.remindBackupSeed()
                || (Constants.SUPPORT_BOTH_BACKUP_WARNINGS && config.remindBackup()))
                && !WalletApplication.getInstance().isBackupDisclaimerDismissed();
        secureActionView.setVisibility(showBackupDisclaimer ? View.VISIBLE : View.GONE);
        findViewById(R.id.secure_action_space).setVisibility(secureActionView.getVisibility());
    }

    private void initNavigationDrawer() {
        final NavigationView navigationView = findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(this);

        viewFakeForSafetySubmenu = new View(this);
        viewFakeForSafetySubmenu.setVisibility(View.GONE);
        viewDrawer = findViewById(R.id.drawer_layout);
        viewDrawer.addView(viewFakeForSafetySubmenu);
        registerForContextMenu(viewFakeForSafetySubmenu);
    }

    @Override
    protected void onResume() {
        super.onResume();

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // delayed start so that UI has enough time to initialize
                getWalletApplication().startBlockchainService(true);
            }
        }, 1000);

        checkLowStorageAlert();
        detectUserCountry();
        showBackupWalletDialogIfNeeded();
        showHideSecureAction();
    }

    private void showBackupWalletDialogIfNeeded() {
        if (showBackupWalletDialog) {
            BackupWalletDialogFragment.show(getSupportFragmentManager());
            showBackupWalletDialog = false;
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacksAndMessages(null);

        super.onPause();
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(final Intent intent) {
        final String action = intent.getAction();

        if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)) {
            final String inputType = intent.getType();
            final NdefMessage ndefMessage = (NdefMessage) intent
                    .getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)[0];
            final byte[] input = Nfc.extractMimePayload(Constants.MIMETYPE_TRANSACTION, ndefMessage);

            new BinaryInputParser(inputType, input) {
                @Override
                protected void handlePaymentIntent(final PaymentIntent paymentIntent) {
                    cannotClassify(inputType);
                }

                @Override
                protected void error(final int messageResId, final Object... messageArgs) {
                    dialog(WalletActivity.this, null, 0, messageResId, messageArgs);
                }
            }.parse();
        }
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode, final String[] permissions,
                                           final int[] grantResults) {
        if (requestCode == REQUEST_CODE_BACKUP_WALLET) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                showBackupWalletDialog = true;
            else
                showDialog(DIALOG_BACKUP_WALLET_PERMISSION);
        } else if (requestCode == REQUEST_CODE_RESTORE_WALLET) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                handleRestoreWallet();
            else
                showDialog(DIALOG_RESTORE_WALLET_PERMISSION);
        }
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final Intent intent) {
        if (requestCode == REQUEST_CODE_SCAN && resultCode == Activity.RESULT_OK) {
            final String input = intent.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT);
            handleString(input, R.string.button_scan, R.string.input_parser_cannot_classify);
        } else {
            super.onActivityResult(requestCode, resultCode, intent);
        }
    }

    private void handleString(String input, final int errorDialogTitleResId, final int cannotClassifyCustomMessageResId) {
        new StringInputParser(input) {
            @Override
            protected void handlePaymentIntent(final PaymentIntent paymentIntent) {
                SendCoinsActivity.start(WalletActivity.this, paymentIntent);
            }

            @Override
            protected void handlePrivateKey(final VersionedChecksummedBytes key) {
                SweepWalletActivity.start(WalletActivity.this, key);
            }

            @Override
            protected void handleDirectTransaction(final Transaction tx) throws VerificationException {
                application.processDirectTransaction(tx);
            }

            @Override
            protected void error(final int messageResId, final Object... messageArgs) {
                dialog(WalletActivity.this, null, errorDialogTitleResId, messageResId, messageArgs);
            }

            @Override
            protected void cannotClassify(String input) {
                log.info("cannot classify: '{}'", input);
                error(cannotClassifyCustomMessageResId, input);
            }
        }.parse();
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.wallet_options, menu);
        super.onCreateOptionsMenu(menu);
        MenuItem walletLockMenuItem = menu.findItem(R.id.wallet_options_lock);
        walletLockMenuItem.setVisible(WalletLock.getInstance().isWalletLocked(wallet));

        hidePersonalSettingsIfLocked();

        return true;
    }

    private void hidePersonalSettingsIfLocked() {
        final NavigationView navigationView = findViewById(R.id.nav_view);
        Menu drawerMenu = navigationView.getMenu();
        boolean walletLocked = WalletLock.getInstance().isWalletLocked(wallet);
        MenuItem addressBookItem = drawerMenu.findItem(R.id.nav_address_book);
        if (addressBookItem != null) {
            addressBookItem.setVisible(!walletLocked);
        }
        View upholdAccountSection = findViewById(R.id.uphold_account_section);
        if (upholdAccountSection != null) {
            upholdAccountSection.setVisibility(walletLocked ? View.GONE : View.VISIBLE);
        }
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case R.id.wallet_options_request:
                handleRequestCoins();
                return true;

            case R.id.wallet_options_send:
                handleSendCoins();
                return true;

			/*case R.id.wallet_options_scan:
                handleScan();
				return true;

			case R.id.wallet_options_address_book:
				AddressBookActivity.start(this);
				return true;

			case R.id.wallet_options_exchange_rates:
				startActivity(new Intent(this, ExchangeRatesActivity.class));
				return true;

			case R.id.wallet_options_sweep_wallet:
				SweepWalletActivity.start(this);
				return true;

			case R.id.wallet_options_network_monitor:
				startActivity(new Intent(this, NetworkMonitorActivity.class));
				return true;

			case R.id.wallet_options_restore_wallet:
				handleRestoreWallet();
				return true;

			case R.id.wallet_options_backup_wallet:
				handleBackupWallet();
				return true;

			case R.id.wallet_options_encrypt_keys:
				handleEncryptKeys();
				return true;

			case R.id.wallet_options_preferences:
				startActivity(new Intent(this, PreferenceActivity.class));
				return true;

			case R.id.wallet_options_safety:
				HelpDialogFragment.page(getFragmentManager(), R.string.help_safety);
				return true;
*/
            case R.id.wallet_options_report_issue:
                handleReportIssue();
                return true;

            case R.id.wallet_options_help:
                HelpDialogFragment.page(getSupportFragmentManager(), R.string.help_wallet);
                return true;

            case R.id.options_paste:
                handlePaste();
                return true;

        }

        return super.onOptionsItemSelected(item);
    }

    public void handleRequestCoins() {
        startActivity(new Intent(this, RequestCoinsActivity.class));
    }

    public void handleSendCoins() {
        startActivity(new Intent(this, SendCoinsActivity.class));
    }

    public void handleScan() {
        startActivityForResult(new Intent(this, ScanActivity.class), REQUEST_CODE_SCAN);
    }

    public void handleBackupWallet() {
        //Only allow to backup when wallet is unlocked
        final WalletLock walletLock = WalletLock.getInstance();
        if (WalletLock.getInstance().isWalletLocked(wallet)) {
            UnlockWalletDialogFragment.show(getSupportFragmentManager(), new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
                    if (!walletLock.isWalletLocked(wallet)) {
                        handleBackupWallet();
                    }
                }
            });
            return;
        }

        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
            BackupWalletDialogFragment.show(getSupportFragmentManager());
        else
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_CODE_BACKUP_WALLET);
    }

    public void handleRestoreWallet() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
            showDialog(DIALOG_RESTORE_WALLET);
        else
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_CODE_RESTORE_WALLET);
    }

    public void handleBackupWalletToSeed() {
        BackupWalletToSeedDialogFragment.show(getSupportFragmentManager());
    }

    public void handleRestoreWalletFromSeed() {
        showRestoreWalletFromSeedDialog();
    }

    public void handleEncryptKeys() {
        EncryptKeysDialogFragment.show(true, getSupportFragmentManager());
    }

    public void handleEncryptKeysRestoredWallet() {
        EncryptKeysDialogFragment.show(false, getSupportFragmentManager(), new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                resetBlockchain();
            }
        });
    }

    private void handleReportIssue() {
        final ReportIssueDialogBuilder dialog = new ReportIssueDialogBuilder(this,
                R.string.report_issue_dialog_title_issue, R.string.report_issue_dialog_message_issue) {
            @Override
            protected CharSequence subject() {
                return Constants.REPORT_SUBJECT_BEGIN + application.packageInfo().versionName + " " + Constants.REPORT_SUBJECT_ISSUE;
            }

            @Override
            protected CharSequence collectApplicationInfo() throws IOException {
                final StringBuilder applicationInfo = new StringBuilder();
                CrashReporter.appendApplicationInfo(applicationInfo, application);
                return applicationInfo;
            }

            @Override
            protected CharSequence collectDeviceInfo() throws IOException {
                final StringBuilder deviceInfo = new StringBuilder();
                CrashReporter.appendDeviceInfo(deviceInfo, WalletActivity.this);
                return deviceInfo;
            }

            @Override
            protected CharSequence collectWalletDump() {
                return application.getWallet().toString(false, true, true, null);
            }
        };
        dialog.show();
    }

    private void handlePaste() {
        String input = null;
        if (clipboardManager.hasPrimaryClip()) {
            final ClipData clip = clipboardManager.getPrimaryClip();
            if (clip == null) {
                return;
            }
            final ClipDescription clipDescription = clip.getDescription();
            if (clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)) {
                final Uri clipUri = clip.getItemAt(0).getUri();
                if (clipUri != null) {
                    input = clipUri.toString();
                }
            } else if (clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) {
                final CharSequence clipText = clip.getItemAt(0).getText();
                if (clipText != null) {
                    input = clipText.toString();
                }
            }
        }
        if (input != null) {
            handleString(input, R.string.scan_to_pay_error_dialog_title, R.string.scan_to_pay_error_dialog_message);
        } else {
            InputParser.dialog(this, null, R.string.scan_to_pay_error_dialog_title, R.string.scan_to_pay_error_dialog_message_no_data);
        }
    }

    private void enableFingerprint() {
        config.setRemindEnableFingerprint(true);
        UnlockWalletDialogFragment.show(getSupportFragmentManager());
    }

    @Override
    protected Dialog onCreateDialog(final int id, final Bundle args) {
        if (id == DIALOG_BACKUP_WALLET_PERMISSION)
            return createBackupWalletPermissionDialog();
        else if (id == DIALOG_RESTORE_WALLET_PERMISSION)
            return createRestoreWalletPermissionDialog();
        else if (id == DIALOG_RESTORE_WALLET)
            return createRestoreWalletDialog();
        else if (id == DIALOG_TIMESKEW_ALERT)
            return createTimeskewAlertDialog(args.getLong("diff_minutes"));
        else if (id == DIALOG_VERSION_ALERT)
            return createVersionAlertDialog();
        else if (id == DIALOG_LOW_STORAGE_ALERT)
            return createLowStorageAlertDialog();
        else
            throw new IllegalArgumentException();
    }

    @Override
    protected void onPrepareDialog(final int id, final Dialog dialog) {
        if (id == DIALOG_RESTORE_WALLET)
            prepareRestoreWalletDialog(dialog);
    }

    private Dialog createBackupWalletPermissionDialog() {
        final DialogBuilder dialog = new DialogBuilder(this);
        dialog.setTitle(R.string.backup_wallet_permission_dialog_title);
        dialog.setMessage(getString(R.string.backup_wallet_permission_dialog_message));
        dialog.singleDismissButton(null);
        return dialog.create();
    }

    private Dialog createRestoreWalletPermissionDialog() {
        return RestoreFromFileHelper.createRestoreWalletPermissionDialog(this);
    }

    private Dialog createRestoreWalletDialog() {
        return RestoreFromFileHelper.createRestoreWalletDialog(this, new RestoreFromFileHelper.OnRestoreWalletListener() {
            @Override
            public void onRestoreWallet(Wallet wallet) {
                restoreWallet(wallet);
            }

            @Override
            public void onRetryRequest() {
                showDialog(DIALOG_RESTORE_WALLET);
            }
        });
    }

    private void prepareRestoreWalletDialog(final Dialog dialog) {
        final boolean hasCoins = wallet.getBalance(Wallet.BalanceType.ESTIMATED).signum() > 0;
        RestoreFromFileHelper.prepareRestoreWalletDialog(this, hasCoins, dialog);
    }

    private void showRestoreWalletFromSeedDialog() {
        RestoreWalletFromSeedDialogFragment.show(getSupportFragmentManager());
    }

    private void checkLowStorageAlert() {
        final Intent stickyIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW));
        if (stickyIntent != null)
            showDialog(DIALOG_LOW_STORAGE_ALERT);
    }

    private Dialog createLowStorageAlertDialog() {
        final DialogBuilder dialog = DialogBuilder.warn(this, R.string.wallet_low_storage_dialog_title);
        dialog.setMessage(R.string.wallet_low_storage_dialog_msg);
        dialog.setPositiveButton(R.string.wallet_low_storage_dialog_button_apps, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(final DialogInterface dialog, final int id) {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
                finish();
            }
        });
        dialog.setNegativeButton(R.string.button_dismiss, null);
        return dialog.create();
    }

    private void checkAlerts() {

        final PackageInfo packageInfo = getWalletApplication().packageInfo();
        final int versionNameSplit = packageInfo.versionName.indexOf('-');
        final HttpUrl.Builder url = HttpUrl
                .parse(Constants.VERSION_URL
                        + (versionNameSplit >= 0 ? packageInfo.versionName.substring(versionNameSplit) : ""))
                .newBuilder();
        url.addEncodedQueryParameter("package", packageInfo.packageName);
        url.addQueryParameter("current", Integer.toString(packageInfo.versionCode));

		/*new HttpGetThread(url.build(), application.httpUserAgent()) {
			@Override
			protected void handleLine(final String line, final long serverTime) {
				final int serverVersionCode = Integer.parseInt(line.split("\\s+")[0]);

				log.info("according to \"" + url + "\", strongly recommended minimum app version is "
						+ serverVersionCode);

				if (serverTime > 0) {
					final long diffMinutes = Math
							.abs((System.currentTimeMillis() - serverTime) / DateUtils.MINUTE_IN_MILLIS);

					if (diffMinutes >= 60) {
						log.info("according to \"" + url + "\", system clock is off by " + diffMinutes + " minutes");

						runOnUiThread(new Runnable() {
							@Override
							public void run() {
								if (!isFinishing())
									return;
								final Bundle args = new Bundle();
								args.putLong("diff_minutes", diffMinutes);
								showDialog(DIALOG_TIMESKEW_ALERT, args);
							}
						});

						return;
					}
				}

				if (serverVersionCode > packageInfo.versionCode) {
					runOnUiThread(new Runnable() {
						@Override
						public void run() {
							if (isFinishing())
								return;
							showDialog(DIALOG_VERSION_ALERT);
						}
					});

					return;
				}
			}

			@Override
			protected void handleException(final Exception x) {
				if (x instanceof UnknownHostException || x instanceof SocketException
						|| x instanceof SocketTimeoutException) {
					// swallow
					log.debug("problem reading", x);
				} else {
					CrashReporter.saveBackgroundTrace(new RuntimeException(url.toString(), x), packageInfo);
				}
			}
		}.start();*/

        if (CrashReporter.hasSavedCrashTrace()) {
            final StringBuilder stackTrace = new StringBuilder();

            try {
                CrashReporter.appendSavedCrashTrace(stackTrace);
            } catch (final IOException x) {
                log.info("problem appending crash info", x);
            }

            final ReportIssueDialogBuilder dialog = new ReportIssueDialogBuilder(this,
                    R.string.report_issue_dialog_title_crash, R.string.report_issue_dialog_message_crash) {
                @Override
                protected CharSequence subject() {
                    return Constants.REPORT_SUBJECT_BEGIN + packageInfo.versionName + " " + Constants.REPORT_SUBJECT_CRASH;
                }

                @Override
                protected CharSequence collectApplicationInfo() throws IOException {
                    final StringBuilder applicationInfo = new StringBuilder();
                    CrashReporter.appendApplicationInfo(applicationInfo, application);
                    return applicationInfo;
                }

                @Override
                protected CharSequence collectStackTrace() throws IOException {
                    if (stackTrace.length() > 0)
                        return stackTrace;
                    else
                        return null;
                }

                @Override
                protected CharSequence collectDeviceInfo() throws IOException {
                    final StringBuilder deviceInfo = new StringBuilder();
                    CrashReporter.appendDeviceInfo(deviceInfo, WalletActivity.this);
                    return deviceInfo;
                }

                @Override
                protected CharSequence collectWalletDump() {
                    return wallet.toString(false, true, true, null);
                }
            };

            dialog.show();
        }
    }

    private Dialog createTimeskewAlertDialog(final long diffMinutes) {
        final PackageManager pm = getPackageManager();
        final Intent settingsIntent = new Intent(android.provider.Settings.ACTION_DATE_SETTINGS);

        final DialogBuilder dialog = DialogBuilder.warn(this, R.string.wallet_timeskew_dialog_title);
        dialog.setMessage(getString(R.string.wallet_timeskew_dialog_msg, diffMinutes));

        if (pm.resolveActivity(settingsIntent, 0) != null) {
            dialog.setPositiveButton(R.string.button_settings, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(final DialogInterface dialog, final int id) {
                    startActivity(settingsIntent);
                    finish();
                }
            });
        }

        dialog.setNegativeButton(R.string.button_dismiss, null);
        return dialog.create();
    }

    private Dialog createVersionAlertDialog() {
        final PackageManager pm = getPackageManager();
        final Intent marketIntent = new Intent(Intent.ACTION_VIEW,
                Uri.parse(String.format(Constants.MARKET_APP_URL, getPackageName())));
        final Intent binaryIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(Constants.BINARY_URL));

        final DialogBuilder dialog = DialogBuilder.warn(this, R.string.wallet_version_dialog_title);
        final StringBuilder message = new StringBuilder(getString(R.string.wallet_version_dialog_msg));
        if (Build.VERSION.SDK_INT < Constants.SDK_DEPRECATED_BELOW)
            message.append("\n\n").append(getString(R.string.wallet_version_dialog_msg_deprecated));
        dialog.setMessage(message);

        if (pm.resolveActivity(marketIntent, 0) != null) {
            dialog.setPositiveButton(R.string.wallet_version_dialog_button_market,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(final DialogInterface dialog, final int id) {
                            startActivity(marketIntent);
                            finish();
                        }
                    });
        }

        if (pm.resolveActivity(binaryIntent, 0) != null) {
            dialog.setNeutralButton(R.string.wallet_version_dialog_button_binary,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(final DialogInterface dialog, final int id) {
                            startActivity(binaryIntent);
                            finish();
                        }
                    });
        }

        dialog.setNegativeButton(R.string.button_dismiss, null);
        return dialog.create();
    }

    public void restoreWallet(final Wallet wallet) {
        application.replaceWallet(wallet);
        getSharedPreferences(Constants.WALLET_LOCK_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit();

        config.disarmBackupReminder();
        this.wallet = application.getWallet();
        upgradeWalletKeyChains(Constants.BIP44_PATH, true);

        if (fingerprintHelper != null) {
            fingerprintHelper.clear();
        }
    }

    private void resetBlockchain() {
        isRestoringBackup = false;
        final DialogBuilder dialog = new DialogBuilder(this);
        dialog.setTitle(R.string.restore_wallet_dialog_success);
        dialog.setMessage(getString(R.string.restore_wallet_dialog_success_replay));
        dialog.setNeutralButton(R.string.button_ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(final DialogInterface dialog, final int id) {
                getWalletApplication().resetBlockchain();
                finish();
            }
        });
        dialog.show();
    }

    private void checkWalletEncryptionDialog() {
        if (!wallet.isEncrypted()) {
            EncryptKeysDialogFragment.show(false, getSupportFragmentManager());
        }
    }

    private void checkRestoredWalletEncryptionDialog() {
        if (!wallet.isEncrypted()) {
            handleEncryptKeysRestoredWallet();
        } else {
            resetBlockchain();
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        MenuInflater inflater = getMenuInflater();
        if (v == viewFakeForSafetySubmenu) {
            inflater.inflate(R.menu.wallet_safety_options, menu);

            final String externalStorageState = Environment.getExternalStorageState();

            menu.findItem(R.id.wallet_options_restore_wallet).setEnabled(
                    Environment.MEDIA_MOUNTED.equals(externalStorageState) || Environment.MEDIA_MOUNTED_READ_ONLY.equals(externalStorageState));
            menu.findItem(R.id.wallet_options_backup_wallet).setEnabled(Environment.MEDIA_MOUNTED.equals(externalStorageState));
            menu.findItem(R.id.wallet_options_encrypt_keys).setTitle(
                    wallet.isEncrypted() ? R.string.wallet_options_encrypt_keys_change : R.string.wallet_options_encrypt_keys_set);

            boolean showFingerprintOption = fingerprintHelper != null && !fingerprintHelper.isFingerprintEnabled();
            menu.findItem(R.id.wallet_options_enable_fingerprint).setVisible(showFingerprintOption);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.wallet_options_safety:
                HelpDialogFragment.page(getSupportFragmentManager(), R.string.help_safety);
                return true;

            case R.id.wallet_options_backup_wallet:
                handleBackupWallet();
                return true;

            case R.id.wallet_options_restore_wallet:
                handleRestoreWallet();
                return true;

            case R.id.wallet_options_encrypt_keys:
                handleEncryptKeys();
                return true;
            case R.id.wallet_options_backup_wallet_to_seed:
                handleBackupWalletToSeed();
                return true;

            case R.id.wallet_options_restore_wallet_from_seed:
                handleRestoreWalletFromSeed();
                return true;

            case R.id.wallet_options_enable_fingerprint:
                enableFingerprint();
                return true;
        }

        return super.onContextItemSelected(item);
    }

    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        // Handle navigation view item clicks here.
        int id = item.getItemId();

        if (id == R.id.nav_home) {

        } else if (id == R.id.nav_address_book) {
            AddressBookActivity.start(this);
        } else if (id == R.id.nav_exchenge_rates) {
            startActivity(new Intent(this, ExchangeRatesActivity.class));
        } else if (id == R.id.nav_paper_wallet) {
            SweepWalletActivity.start(this);
        } else if (id == R.id.nav_network_monitor) {
            startActivity(new Intent(this, NetworkMonitorActivity.class));
        } else if (id == R.id.nav_safety) {
            openContextMenu(viewFakeForSafetySubmenu);
        } else if (id == R.id.nav_settings) {
            startActivity(new Intent(this, PreferenceActivity.class));
        } else if (id == R.id.nav_disconnect) {
            handleDisconnect();
        } else if (id == R.id.nav_report_issue) {
            handleReportIssue();
        }

        viewDrawer.closeDrawer(GravityCompat.START);
        return true;
    }

    @Override
    public void onBackPressed() {
        if (viewDrawer.isDrawerOpen(GravityCompat.START)) {
            viewDrawer.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    private void startUpholdActivity() {
        startActivity(UpholdAccountActivity.createIntent(this, wallet));
    }

    //Dash Specific
    private void handleDisconnect() {
        getWalletApplication().stopBlockchainService();
        finish();
    }

    public void upgradeWalletKeyChains(final ImmutableList<ChildNumber> path, final boolean restoreBackup) {

        isRestoringBackup = restoreBackup;
        if (!wallet.hasKeyChain(path)) {
            if (wallet.isEncrypted()) {
                EncryptNewKeyChainDialogFragment.show(getSupportFragmentManager(), path);
            } else {
                //
                // Upgrade the wallet now
                //
                wallet.addKeyChain(path);
                application.saveWallet();
                //
                // Tell the user that the wallet is being upgraded (BIP44)
                // and they will have to enter a PIN.
                //
                UpgradeWalletDisclaimerDialog.show(getSupportFragmentManager());
            }
        } else {
            if (restoreBackup) {
                checkRestoredWalletEncryptionDialog();
            } else
                checkWalletEncryptionDialog();
        }
    }

    //BIP44 Wallet Upgrade Dialog Dismissed (Ok button pressed)
    @Override
    public void onUpgradeConfirmed() {
        if (isRestoringBackup) {
            checkRestoredWalletEncryptionDialog();
        } else {
            checkWalletEncryptionDialog();
        }
    }

    @Override
    public void onNewKeyChainEncrypted() {

    }

    /**
     * Get ISO 3166-1 alpha-2 country code for this device (or null if not available)
     * If available, call {@link #showFiatCurrencyChangeDetectedDialog(String, String)}
     * passing the country code.
     */
    private void detectUserCountry() {
        if (config.getExchangeCurrencyCodeDetected()) {
            return;
        }
        try {
            final TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            final String simCountry = tm.getSimCountryIso();
            if (simCountry != null && simCountry.length() == 2) { // SIM country code is available
                updateCurrencyExchange(simCountry.toUpperCase());
            } else if (tm.getPhoneType() != TelephonyManager.PHONE_TYPE_CDMA) { // device is not 3G (would be unreliable)
                String networkCountry = tm.getNetworkCountryIso();
                if (networkCountry != null && networkCountry.length() == 2) { // network country code is available
                    updateCurrencyExchange(networkCountry.toUpperCase());
                } else {
                    //Couldn't obtain country code - Use Default
                    if (config.getExchangeCurrencyCode() == null)
                        config.setExchangeCurrencyCode(Constants.DEFAULT_EXCHANGE_CURRENCY);
                }
            } else {
                //No cellular network - Wifi Only
                if (config.getExchangeCurrencyCode() == null)
                    config.setExchangeCurrencyCode(Constants.DEFAULT_EXCHANGE_CURRENCY);
            }
        } catch (Exception e) {
            //fail safe
        }
    }

    /**
     * Check whether app was ever updated or if it is an installation that was never updated.
     * Show dialog to update if it's being updated or change it automatically.
     *
     * @param countryCode countryCode ISO 3166-1 alpha-2 country code.
     */
    private void updateCurrencyExchange(String countryCode) {
        Locale l = new Locale("", countryCode);
        Currency currency = Currency.getInstance(l);
        String newCurrencyCode = currency.getCurrencyCode();
        String currentCurrencyCode = config.getExchangeCurrencyCode();
        if (currentCurrencyCode == null) {
            currentCurrencyCode = Constants.DEFAULT_EXCHANGE_CURRENCY;
        }

        if (!currentCurrencyCode.equalsIgnoreCase(newCurrencyCode)) {
            if (config.wasUpgraded()) {
                showFiatCurrencyChangeDetectedDialog(currentCurrencyCode, newCurrencyCode);
            } else {
                config.setExchangeCurrencyCodeDetected(true);
                config.setExchangeCurrencyCode(newCurrencyCode);
            }
        }

        //Fallback to default
        if (config.getExchangeCurrencyCode() == null) {
            config.setExchangeCurrencyCode(Constants.DEFAULT_EXCHANGE_CURRENCY);
        }
    }

    /**
     * Show a Dialog and if user confirms it, set the default fiat currency exchange rate using
     * the country code to generate a Locale and get the currency code from it.
     *
     * @param newCurrencyCode currency code.
     */
    private void showFiatCurrencyChangeDetectedDialog(final String currentCurrencyCode,
                                                      final String newCurrencyCode) {
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(this);
        dialogBuilder.setMessage(getString(R.string.change_exchange_currency_code_message,
                newCurrencyCode, currentCurrencyCode));
        dialogBuilder.setPositiveButton(getString(R.string.change_to, newCurrencyCode), new OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                config.setExchangeCurrencyCodeDetected(true);
                config.setExchangeCurrencyCode(newCurrencyCode);
                WalletBalanceWidgetProvider.updateWidgets(WalletActivity.this, wallet);
            }
        });
        dialogBuilder.setNegativeButton(getString(R.string.leave_as, currentCurrencyCode), new OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                config.setExchangeCurrencyCodeDetected(true);
            }
        });
        dialogBuilder.show();
    }

    @Override
    public MotionLayout getMotionLayout() {
        return findViewById(R.id.home_content);
    }

    @Override
    public RecyclerView getRecyclerView() {
        return findViewById(R.id.home_recycler);
    }
}
