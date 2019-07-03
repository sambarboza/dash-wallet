package de.schildbach.wallet.ui;

import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentStatePagerAdapter;

/**
 * @author Samuel Barbosa
 */
public class ContactsPagerAdapter extends FragmentStatePagerAdapter {

    public ContactsPagerAdapter(FragmentManager fm) {
        super(fm);
    }

    @Override
    public Fragment getItem(int i) {
        ContactsFragment.ContactsType type = ContactsFragment.ContactsType.values()[i];
        return ContactsFragment.newInstance(type);
    }

    @Override
    public int getCount() {
        return 3;
    }

    @Nullable
    @Override
    public CharSequence getPageTitle(int position) {
        switch (position) {
            case 0:
                return "Contacts";
            case 1:
                return "Pending";
            case 2:
                return "Requests";
        }
        return "";
    }

}
