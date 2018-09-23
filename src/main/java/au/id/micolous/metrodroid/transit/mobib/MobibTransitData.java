/*
 * MobibTransitData.java
 *
  * Copyright 2018 Google
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

package au.id.micolous.metrodroid.transit.mobib;

import android.os.Parcel;
import android.support.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import au.id.micolous.farebot.R;
import au.id.micolous.metrodroid.MetrodroidApplication;
import au.id.micolous.metrodroid.card.CardType;
import au.id.micolous.metrodroid.card.calypso.CalypsoApplication;
import au.id.micolous.metrodroid.card.iso7816.ISO7816File;
import au.id.micolous.metrodroid.card.iso7816.ISO7816Record;
import au.id.micolous.metrodroid.transit.CardInfo;
import au.id.micolous.metrodroid.transit.TransitIdentity;
import au.id.micolous.metrodroid.transit.Trip;
import au.id.micolous.metrodroid.transit.en1545.Calypso1545TransitData;
import au.id.micolous.metrodroid.transit.en1545.En1545Container;
import au.id.micolous.metrodroid.transit.en1545.En1545Field;
import au.id.micolous.metrodroid.transit.en1545.En1545FixedHex;
import au.id.micolous.metrodroid.transit.en1545.En1545FixedInteger;
import au.id.micolous.metrodroid.transit.en1545.En1545FixedString;
import au.id.micolous.metrodroid.transit.en1545.En1545Lookup;
import au.id.micolous.metrodroid.transit.en1545.En1545Parsed;
import au.id.micolous.metrodroid.transit.en1545.En1545Parser;
import au.id.micolous.metrodroid.transit.en1545.En1545Repeat;
import au.id.micolous.metrodroid.transit.en1545.En1545Subscription;
import au.id.micolous.metrodroid.transit.en1545.En1545Transaction;
import au.id.micolous.metrodroid.ui.ListItem;
import au.id.micolous.metrodroid.util.Utils;

/*
 * Reference:
 * - https://github.com/zoobab/mobib-extractor
 */
public class MobibTransitData extends Calypso1545TransitData {
    // 56 = Belgium
    private static final int MOBIB_NETWORK_ID = 0x56001;
    private static final String NAME = "Mobib";
    private final En1545Parsed mExtHolderParsed;
    private final int mPurchase;
    private final int mTotalTrips;

    public static final CardInfo CARD_INFO = new CardInfo.Builder()
            .setName(MobibTransitData.NAME)
            .setCardType(CardType.ISO7816)
            .setLocation(R.string.location_brussels)
            .build();

    public static final TimeZone TZ = TimeZone.getTimeZone("Europe/Bruxelles");

    public static final Creator<MobibTransitData> CREATOR = new Creator<MobibTransitData>() {
        @NonNull
        public MobibTransitData createFromParcel(Parcel parcel) {
            return new MobibTransitData(parcel);
        }

        @NonNull
        public MobibTransitData[] newArray(int size) {
            return new MobibTransitData[size];
        }
    };

    private final static En1545Container ticketEnvFields = new En1545Container(
            new En1545FixedInteger("EnvUnknownA", 13),
            new En1545FixedInteger("EnvNetworkId", 24),
            new En1545FixedInteger("EnvUnknownB", 9),
            En1545FixedInteger.date("EnvApplicationValidityEnd"),
            new En1545FixedInteger("EnvUnknownC", 6),
            new En1545FixedInteger("HolderBirthDate", 32),
            new En1545FixedHex("EnvCardSerial", 76),
            new En1545FixedInteger("EnvUnknownD", 5),
            new En1545FixedInteger("HolderZip", 14),
            new En1545FixedHex("EnvUnknownE", 34)
    );
    private final static En1545Container extHolderFields = new En1545Container(
            new En1545FixedInteger("ExtHolderUnknownA", 18),
            new En1545FixedHex("CardSerial", 76),
            new En1545FixedInteger("ExtHolderUnknownB", 16),
            new En1545FixedHex("ExtHolderUnknownC", 58),
            new En1545FixedInteger("DateOfBirth", 32),
            new En1545FixedInteger("HolderGender", 2),
            new En1545FixedInteger("ExtHolderUnknownD", 3),
            new En1545FixedString("HolderName", 259)
    );

    private static final En1545Field contractListFields = new En1545Repeat(4,
            new En1545Container(
                new En1545FixedHex("CLunknownA", 18),
                    new En1545FixedInteger("ContractsPointer", 5),
                    new En1545FixedHex("CLunknownB", 16)
            )
    );

    private MobibTransitData(CalypsoApplication card) {
        super(card, ticketEnvFields, contractListFields, getSerial(card));
        ISO7816File holderFile = card.getFile(CalypsoApplication.File.HOLDER_EXTENDED);
        byte[] holder = Utils.concatByteArrays(holderFile.getRecord(1).getData(),
                holderFile.getRecord(2).getData());
        mExtHolderParsed = En1545Parser.parse(holder, extHolderFields);
        mPurchase = Utils.getBitsFromBuffer(card.getFile(CalypsoApplication.File.EP_LOAD_LOG)
                        .getRecord(1).getData(),
                2, 14);
        int totalTrips = 0;
        for (ISO7816Record record : card.getFile(CalypsoApplication.File.TICKETING_LOG).getRecords()) {
            int tripCtr = Utils.getBitsFromBuffer(record.getData(), 17 * 8 + 3,
                    23);
            if (totalTrips < tripCtr)
                totalTrips = tripCtr;
        }
        mTotalTrips = totalTrips;
    }

    @Override
    public List<ListItem> getInfo() {
        ArrayList <ListItem> li = new ArrayList<>();
        if (mPurchase != 0)
            li.add(new ListItem(R.string.en1545_card_purchase_date,
                    Utils.longDateFormat(En1545FixedInteger.parseDate(mPurchase, TZ))));
        li.add(new ListItem(R.string.mobib_total_trips, Integer.toString(mTotalTrips)));
        int gender = mExtHolderParsed.getIntOrZero("HolderGender");
        if (gender == 0) {
            li.add(new ListItem(R.string.mobib_card_type, R.string.mobib_anon));
        } else {
            li.add(new ListItem(R.string.mobib_card_type, R.string.mobib_personal));
        }
        if (gender != 0 && !MetrodroidApplication.hideCardNumbers()
                && !MetrodroidApplication.obfuscateTripDates()) {
            li.add(new ListItem(R.string.mobib_card_holder,
                    mExtHolderParsed.getString("HolderName")));
            switch (gender) {
                case 1:
                    li.add(new ListItem(R.string.en1545_card_holder_gender,
                            R.string.en1545_card_holder_male));
                    break;
                case 2:
                    li.add(new ListItem(R.string.en1545_card_holder_gender,
                            R.string.en1545_card_holder_female));
                    break;
                default:
                    li.add(new ListItem(R.string.en1545_card_holder_gender,
                            Integer.toHexString(gender)));
                    break;
            }
        }
        HashSet<String> handled = new HashSet<>(Arrays.asList(
                "EnvApplicationValidityEndDate",
                "DateOfBirth",
                "CardSerial",
                "HolderGender",
                "HolderName",

                "ExtHolderUnknownA", "ExtHolderUnknownB",
                "ExtHolderUnknownC", "ExtHolderUnknownD"));
        li.addAll(mExtHolderParsed.getInfo(handled));
        li.addAll(super.getInfo());
        return li;
    }

    @Override
    protected En1545Lookup getLookup() {
        return MobibLookup.getInstance();
    }

    public static String getSerial(CalypsoApplication card) {
        byte[] holder = card.getFile(CalypsoApplication.File.HOLDER_EXTENDED).getRecord(1).getData();
        return String.format(Locale.ENGLISH,
                "%06d%06d%06d%01d",
                Utils.convertBCDtoInteger(Utils.getBitsFromBuffer(holder, 18, 24)),
                Utils.convertBCDtoInteger(Utils.getBitsFromBuffer(holder, 42, 24)),
                Utils.convertBCDtoInteger(Utils.getBitsFromBuffer(holder, 66, 24)),
                Utils.convertBCDtoInteger(Utils.getBitsFromBuffer(holder, 90, 4)));
    }

    @NonNull
    public static TransitIdentity parseTransitIdentity(CalypsoApplication card) {
        return new TransitIdentity(NAME, getSerial(card));
    }

    public static boolean check(byte[] ticketEnv) {
        try {
            int networkID = Utils.getBitsFromBuffer(ticketEnv, 13, 24);
            return MOBIB_NETWORK_ID == networkID;
        } catch (Exception e) {
            return false;
        }
    }

    @NonNull
    public static MobibTransitData parseTransitData(CalypsoApplication card) {
        return new MobibTransitData(card);
    }

    @Override
    protected En1545Subscription createSubscription(CalypsoApplication card, byte[] data, En1545Parsed contractList, Integer listNum, int recordNum) {
        byte[] ctr9 = card.getFile(CalypsoApplication.File.TICKETING_COUNTERS_9).getRecord(1).getData();
        return new MobibSubscription(data,
                Utils.byteArrayToInt(ctr9, (recordNum - 1) * 3, 3),
                recordNum);
    }

    @Override
    protected En1545Transaction createTrip(byte[] data) {
        return new MobibTransaction(data);
    }

    @Override
    public String getCardName() {
        return NAME;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        mExtHolderParsed.writeToParcel(dest, flags);
        dest.writeInt(mPurchase);
        dest.writeInt(mTotalTrips);
    }

    private MobibTransitData(Parcel parcel) {
        super(parcel);
        mExtHolderParsed = new En1545Parsed(parcel);
        mPurchase = parcel.readInt();
        mTotalTrips = parcel.readInt();
    }
}
