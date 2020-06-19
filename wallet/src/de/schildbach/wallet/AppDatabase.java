package de.schildbach.wallet;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;

import de.schildbach.wallet.data.BlockchainIdentityData;
import de.schildbach.wallet.data.BlockchainIdentityDataDao;
import de.schildbach.wallet.data.BlockchainIdentityDataDaoAsync;
import de.schildbach.wallet.data.BlockchainState;
import de.schildbach.wallet.data.BlockchainStateDao;
import de.schildbach.wallet.data.DashPayContactRequest;
import de.schildbach.wallet.data.DashPayContactRequestDao;
import de.schildbach.wallet.data.DashPayContactRequestDaoAsync;
import de.schildbach.wallet.data.DashPayProfile;
import de.schildbach.wallet.data.DashPayProfileDao;
import de.schildbach.wallet.data.DashPayProfileDaoAsync;
import de.schildbach.wallet.data.RoomConverters;
import de.schildbach.wallet.rates.ExchangeRate;
import de.schildbach.wallet.rates.ExchangeRatesDao;

@Database(entities = {ExchangeRate.class, BlockchainState.class, BlockchainIdentityData.class, DashPayProfile.class, DashPayContactRequest.class}, version = 11)
@TypeConverters({RoomConverters.class})
public abstract class AppDatabase extends RoomDatabase {

    private static AppDatabase instance;

    public abstract ExchangeRatesDao exchangeRatesDao();

    public abstract BlockchainStateDao blockchainStateDao();

    public abstract BlockchainIdentityDataDao blockchainIdentityDataDao();

    public abstract BlockchainIdentityDataDaoAsync blockchainIdentityDataDaoAsync();

    public abstract DashPayProfileDao dashPayProfileDao();

    public abstract DashPayProfileDaoAsync dashPayProfileDaoAsync();

    public abstract DashPayContactRequestDao dashPayContactRequestDao();

    public abstract DashPayContactRequestDaoAsync dashPayContactRequestDaoAsync();

    public static AppDatabase getAppDatabase() {
        if (instance == null) {
            instance = Room.databaseBuilder(WalletApplication.getInstance(), AppDatabase.class,
                    "dash-wallet-database").fallbackToDestructiveMigration().build();
        }
        return instance;
    }

}
