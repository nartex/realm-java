/*
 * Copyright 2014 Realm Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.realm.internal;

import java.io.Closeable;
import java.io.IOError;
import java.lang.*;

import io.realm.exceptions.RealmIOException;

public class SharedGroup implements Closeable {

    public static final boolean IMPLICIT_TRANSACTION = true;
    public static final boolean EXPLICIT_TRANSACTION = false;

    private static final boolean CREATE_FILE_YES = false;
    private static final boolean CREATE_FILE_NO = true;
    private static final boolean ENABLE_REPLICATION = true;
    private static final boolean DISABLE_REPLICATION = false;

    private final String path;
    private long nativePtr;
    private long nativeReplicationPtr;
    private boolean implicitTransactionsEnabled = false;
    private boolean activeTransaction;
    private final Context context;

    static {
        RealmCore.loadLibrary();
    }

    public enum Durability {
        FULL(0),
        MEM_ONLY(1);
        //ASYNC(2); // TODO: re-enable when possible

        final int value;

        Durability(int value) {
            this.value = value;
        }
    }

    public SharedGroup(String databaseFile) {
        context = new Context();
        path = databaseFile;
        nativePtr = nativeCreate(databaseFile, Durability.FULL.value, CREATE_FILE_YES, DISABLE_REPLICATION, null);
        checkNativePtrNotZero();
    }

    public SharedGroup(String canonicalPath, boolean enableImplicitTransactions, Durability durability, byte[] key) {
        if (enableImplicitTransactions) {
            nativeReplicationPtr = nativeCreateReplication(canonicalPath, key);
            nativePtr = createNativeWithImplicitTransactions(nativeReplicationPtr, durability.value, key);
            implicitTransactionsEnabled = true;
        } else {
            nativePtr = nativeCreate(canonicalPath, Durability.FULL.value, CREATE_FILE_YES, DISABLE_REPLICATION, key);
        }
        context = new Context();
        path = canonicalPath;
        checkNativePtrNotZero();
    }

    public SharedGroup(String canonicalPath, Durability durability, byte[] key) {
        path = canonicalPath;
        context = new Context();
        nativePtr = nativeCreate(canonicalPath, durability.value, false, false, key);
        checkNativePtrNotZero();
    }

    void advanceRead() {
        nativeAdvanceRead(nativePtr, nativeReplicationPtr);
    }

    void promoteToWrite() {
        nativePromoteToWrite(nativePtr, nativeReplicationPtr);
    }

    void commitAndContinueAsRead() {
        nativeCommitAndContinueAsRead(nativePtr);
    }

    void rollbackAndContinueAsRead() {
        nativeRollbackAndContinueAsRead(nativePtr, nativeReplicationPtr);
    }

    public ImplicitTransaction beginImplicitTransaction() {
        if (activeTransaction) {
            throw new IllegalStateException(
                    "Can't beginImplicitTransaction() during another active transaction");
        }
        long nativeGroupPtr = nativeBeginImplicit(nativePtr);
        ImplicitTransaction transaction = new ImplicitTransaction(context, this, nativeGroupPtr);
        activeTransaction = true;
        return transaction;
    }

    public WriteTransaction beginWrite() {
        if (activeTransaction)
            throw new IllegalStateException(
                    "Can't beginWrite() during another active transaction");
        // FIXME: throw from nativeMethod in case of error

        long nativeWritePtr = nativeBeginWrite(nativePtr);
        try {
            // Copy context reference from parent
            WriteTransaction t = new WriteTransaction(context, this, nativeWritePtr);
            activeTransaction = true;
            return t;
        } catch (RuntimeException e) {
            Group.nativeClose(nativeWritePtr);
            throw e;
        }
    }

    public ReadTransaction beginRead() {
        if (activeTransaction)
            throw new IllegalStateException("Can't beginRead() during another active transaction");
        // FIXME: throw from nativeMethod in case of error

        long nativeReadPtr = nativeBeginRead(nativePtr);
        try {
            // Copy context reference from parent
            ReadTransaction t = new ReadTransaction(context, this, nativeReadPtr);
            activeTransaction = true;
            return t;
        } catch (RuntimeException e) {
            Group.nativeClose(nativeReadPtr);
            throw e;
        }
    }

    void endRead() {
        if (isClosed())
            throw new IllegalStateException("Can't endRead() on closed group. ReadTransaction is invalid.");
        nativeEndRead(nativePtr);
        activeTransaction = false;
    }

    public void close() {
        synchronized (context) {
            if (nativePtr != 0) {
                nativeClose(nativePtr);
                nativePtr = 0;
                if (implicitTransactionsEnabled && nativeReplicationPtr != 0) {
                    nativeCloseReplication(nativeReplicationPtr);
                    nativeReplicationPtr = 0;
                }
            }
        }
    }

    protected void finalize() {
        synchronized (context) {
            if (nativePtr != 0) {
                context.asyncDisposeSharedGroup(nativePtr);
                nativePtr = 0; // Set to 0 if finalize is called before close() for some reason
                if (implicitTransactionsEnabled && nativeReplicationPtr != 0) {
                    nativeCloseReplication(nativeReplicationPtr);
                    nativeReplicationPtr = 0;
                }
            }
        }
    }

    void commit() {
        if (isClosed())
            throw new IllegalStateException(
                    "Can't commit() on closed group. WriteTransaction is invalid.");
        nativeCommit(nativePtr);
        activeTransaction = false;
    }

    void rollback() {
        if (isClosed())
            throw new IllegalStateException(
                    "Can't rollback() on closed group. WriteTransaction is invalid.");
        nativeRollback(nativePtr);
        activeTransaction = false;
    }

    boolean isClosed() {
        return nativePtr == 0;
    }

    public boolean hasChanged() {
        return nativeHasChanged(nativePtr);
    }

    public void reserve(long bytes) {
        nativeReserve(nativePtr, bytes);
    }

    /**
     * Compacts a shared group. This will block access to the shared group until done.
     *
     * @return True if compaction succeeded, false otherwise.
     * @throws RuntimeException if using this within either a read or or write transaction.
     */
    public boolean compact() {
        return nativeCompact(nativePtr);
    }

    /**
     * Returns the absolute path to the file backing this SharedGroup.
     *
     * @return Canonical path to the Realm file.
     */
    public String getPath() {
        return path;
    }

    private void checkNativePtrNotZero() {
        if (this.nativePtr == 0) {
            throw new IOError(new RealmIOException("Realm could not be opened"));
        }
    }

    private native long createNativeWithImplicitTransactions(long nativeReplicationPtr, int durability, byte[] key);
    private native long nativeCreateReplication(String databaseFile, byte[] key);
    private native void nativeAdvanceRead(long nativePtr, long native_replication_ptr);
    private native void nativePromoteToWrite(long nativePtr, long native_replication_ptr);
    private native void nativeCommitAndContinueAsRead(long nativePtr);
    private native void nativeRollbackAndContinueAsRead(long nativePtr, long native_replication_ptr);
    private native long nativeBeginImplicit(long nativePtr);
    private native String nativeGetDefaultReplicationDatabaseFileName();
    private native void nativeReserve(long nativePtr, long bytes);
    private native boolean nativeHasChanged(long nativePtr);
    private native long nativeBeginRead(long nativePtr);
    private native void nativeEndRead(long nativePtr);
    private native long nativeBeginWrite(long nativePtr);
    private native void nativeCommit(long nativePtr);
    private native void nativeRollback(long nativePtr);
    private native long nativeCreate(String databaseFile,
                                     int durabilityValue,
                                     boolean dontCreateFile,
                                     boolean enableReplication,
                                     byte[] key);
    private native boolean nativeCompact(long nativePtr);
    protected static native void nativeClose(long nativePtr);
    private native void nativeCloseReplication(long nativeReplicationPtr);
}
