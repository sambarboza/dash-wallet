/*
 * Copyright © 2019 Dash Core Group. All rights reserved.
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

import android.app.Dialog;
import android.app.ProgressDialog;
import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.ViewModelProviders;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.dash.wallet.common.ui.DialogBuilder;
import org.dash.wallet.common.util.ProgressDialogUtils;
import org.spongycastle.crypto.params.KeyParameter;

import java.util.Arrays;
import java.util.regex.Pattern;

import de.schildbach.wallet.data.ErrorType;
import de.schildbach.wallet.data.LoadingType;
import de.schildbach.wallet.data.Resource;
import de.schildbach.wallet.data.StatusType;
import de.schildbach.wallet.data.SuccessType;
import de.schildbach.wallet_test.R;

/**
 * @author Samuel Barbosa
 */
public class AddContactDialog extends DialogFragment {

    private static final String ARG_ENCRYPTION_KEY = "arg_encryption_key";
    private ProgressDialog loadingDialog;
    private ContactsViewModel viewModel;

    public static void show(FragmentManager fm, KeyParameter encryptionKey) {
        Bundle args = new Bundle();
        args.putByteArray(ARG_ENCRYPTION_KEY, encryptionKey.getKey());

        AddContactDialog dialog = new AddContactDialog();
        dialog.setArguments(args);
        dialog.show(fm, AddContactDialog.class.getSimpleName());
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        final FragmentActivity activity = getActivity();
        viewModel = ViewModelProviders.of(activity).get(ContactsViewModel.class);
        final View view = LayoutInflater.from(activity).inflate(R.layout.create_blockchain_user_dialog,
                null);
        final TextView usernameTextView = (TextView) view.findViewById(R.id.buname);

        DialogBuilder dialogBuilder = new DialogBuilder(activity);
        dialogBuilder.setView(view);
        dialogBuilder.setTitle(R.string.contact_add);
        dialogBuilder.setPositiveButton(android.R.string.ok, null);

        Dialog dialog = dialogBuilder.create();

        dialog.setOnShowListener(dialogInterface -> {
            Button addBtn = ((AlertDialog) dialog).getButton(DialogInterface.BUTTON_POSITIVE);
            addBtn.setEnabled(false);
            Pattern pattern = Pattern.compile("[^a-z0-9._]");
            usernameTextView.addTextChangedListener(new TextWatcher() {
                @Override
                public void afterTextChanged(Editable editable) {
                    boolean valid = !pattern.matcher(editable.toString()).find();
                    int length = editable.length();
                    valid = valid && length >= 3 && length <= 24;
                    addBtn.setEnabled(valid);
                }

                @Override
                public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

                }

                @Override
                public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

                }
            });
            addBtn.setOnClickListener(v -> {
                addContact(usernameTextView.getText().toString());
            });
        });

        return dialog;
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        hideLoading();
        super.onDismiss(dialog);
    }

    private void addContact(String username) {
        LiveData<Resource<String>> liveData = viewModel
                .addContact(username, getArguments().getByteArray(ARG_ENCRYPTION_KEY));
        liveData.observe(getActivity(), resource -> {
            StatusType status = resource.status;
            if (Arrays.asList(SuccessType.values()).contains(status)) {
                String contactAddedMessage = getActivity()
                        .getString(R.string.contact_add_success, username);
                Toast.makeText(getActivity(), contactAddedMessage, Toast.LENGTH_SHORT).show();
                dismiss();
            } else if (Arrays.asList(LoadingType.values()).contains(status)) {
                showLoading();
            } else {
                hideLoading();
                if (ErrorType.INSUFFICIENT_MONEY.equals(status)) {
                    Toast.makeText(getActivity(), R.string.send_coins_fragment_insufficient_money_title,
                            Toast.LENGTH_SHORT).show();
                } else if (ErrorType.TX_REJECT_DUP_USERNAME.equals(status)) {
                    Toast.makeText(getActivity(), R.string.blockchain_user_duplicated_username_error,
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(getActivity(), R.string.unknown_error, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void showLoading() {
        if (loadingDialog == null) {
            loadingDialog = ProgressDialogUtils.createSpinningLoading(getActivity());
        }
        loadingDialog.show();
    }

    private void hideLoading() {
        if (loadingDialog != null) {
            loadingDialog.cancel();
        }
    }

}
