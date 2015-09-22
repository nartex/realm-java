package io.realm.examples.intro.model;

import android.os.Parcel;

import org.parceler.Parcels;

// Specific class for a RealmList<Bar> field
public class CatListParcelConverter extends RealmListParcelConverter<Cat> {

    @Override
    public void itemToParcel(Cat input, Parcel parcel) {
        parcel.writeParcelable(Parcels.wrap(input), 0);
    }

    @Override
    public Cat itemFromParcel(Parcel parcel) {
        return Parcels.unwrap(parcel.readParcelable(Cat.class.getClassLoader()));
    }
}