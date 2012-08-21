/**
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012 ForgeRock AS Inc. All Rights Reserved
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * https://opensso.dev.java.net/public/CDDLv1.0.html or
 * opensso/legal/CDDLv1.0.txt
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at opensso/legal/CDDLv1.0.txt.
 *
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 * Portions Copyrighted [2010-2012] [ForgeRock AS]
 *
 */

package org.forgerock.openam.session.ha.amsessionstore.store.opendj;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Iterator;

import com.iplanet.am.util.SystemProperties;
import com.iplanet.dpro.session.SessionID;
import com.iplanet.dpro.session.service.AMSessionRepository;
import com.iplanet.dpro.session.service.InternalSession;
import com.iplanet.dpro.session.service.SessionService;
import com.sun.identity.common.GeneralTaskRunnable;
import com.sun.identity.ha.FAMPersisterManager;
import com.sun.identity.session.util.SessionUtils;
import com.sun.identity.shared.debug.Debug;
import org.forgerock.openam.session.model.*;
import org.opends.server.types.AttributeValue;
import org.forgerock.i18n.LocalizableMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

import org.forgerock.openam.session.ha.amsessionstore.common.Constants;
import org.forgerock.openam.session.ha.amsessionstore.common.Log;
import com.iplanet.dpro.session.exceptions.NotFoundException;
import com.iplanet.dpro.session.exceptions.StoreException;
import org.opends.server.core.AddOperation;
import org.opends.server.core.DeleteOperation;
import org.opends.server.core.ModifyOperation;
import org.opends.server.protocols.internal.InternalClientConnection;
import org.opends.server.protocols.internal.InternalSearchOperation;
import org.opends.server.protocols.ldap.LDAPModification;
import org.opends.server.types.Attribute;
import org.opends.server.types.DereferencePolicy;
import org.opends.server.types.DirectoryException;
import org.opends.server.types.ModificationType;
import org.opends.server.types.RawAttribute;
import org.opends.server.types.RawModification;
import org.opends.server.types.ResultCode;
import org.opends.server.types.SearchResultEntry;
import org.opends.server.types.SearchScope;

import javax.jms.*;
import javax.jms.IllegalStateException;

import static org.forgerock.openam.session.ha.i18n.AmsessionstoreMessages.*;

/**
 * Provide Implementation of AMSessionRepository using the internal
 * Configuration Directory as OpenAM's Session and Token Store.
 * <p/>
 * Having a replicated Directory
 * will provide the underlying SFO/HA for OpenAM Session Data.
 *
 * @author steve
 */
public class OpenDJPersistentStore extends GeneralTaskRunnable implements AMSessionRepository {

    /**
     * Debug Logging
     */
    private static Debug debug = SessionService.sessionDebug;

    /**
     * Service Globals
     */
    private boolean shutdown = false;
    private Thread storeThread;
    private int sleepInterval = 60 * 1000;
    private final static String ID = "OpenDJPersistentStore";

    /**
     * Grace Period
     */
    private static long gracePeriod = 5 * 60; /* 5 mins in secs */

    /**
     * Configuration Aspect Definitions
     */
    static public final String SESSION = "session";

    private static boolean caseSensitiveUUID =
            SystemProperties.getAsBoolean(com.sun.identity.shared.Constants.CASE_SENSITIVE_UUID);


    /**
     * Internal LDAP Connection.
     */
    private static InternalClientConnection icConn;

    /**
     * Directory Constructs
     */
    private static LinkedHashSet<String> serverAttrs;

    /**
     * Search Constructs
     */
    private final static String SKEY_FILTER_PRE = "(sKey=";
    private final static String SKEY_FILTER_POST = ")";
    private final static String EXPDATE_FILTER_PRE = "(expirationDate<=";
    private final static String EXPDATE_FILTER_POST = ")";
    private final static String NUM_SUB_ORD_ATTR = "numSubordinates";
    private final static String ALL_ATTRS = "(" + NUM_SUB_ORD_ATTR + "=*)";
    private static LinkedHashSet<String> returnAttrs;
    private static LinkedHashSet<String> numSubOrgAttrs;

    /**
     * Deferred Operation Queue.
     */
    private static ConcurrentLinkedQueue<AMSessionRepositoryDeferredOperation>
            amSessionRepositoryDeferredOperationConcurrentLinkedQueue
            = new ConcurrentLinkedQueue<AMSessionRepositoryDeferredOperation>();

    /**
     * Initialize Singleton Service Implementation
     */
    static {
        debug = Debug.getInstance(DEBUG_NAME);
        initialize();
    }

    /**
     * Perform Initialization
     */
    private static void initialize() {
        debug.error("Initializing OpenAM Session Repository using Implementation Class: " +
                OpenDJPersistentStore.class.getClass().getSimpleName());

        System.out.println("Initializing OpenAM Session Repository using Implementation Class: " +
                OpenDJPersistentStore.class.getClass().getSimpleName());

        returnAttrs = new LinkedHashSet<String>();
        returnAttrs.add("dn");
        returnAttrs.add(AMRecordDataEntry.PRI_KEY);
        returnAttrs.add(AMRecordDataEntry.SEC_KEY);
        returnAttrs.add(AMRecordDataEntry.AUX_DATA);
        returnAttrs.add(AMRecordDataEntry.DATA);
        returnAttrs.add(AMRecordDataEntry.EXP_DATE);
        returnAttrs.add(AMRecordDataEntry.EXTRA_BYTE_ATTR);
        returnAttrs.add(AMRecordDataEntry.EXTRA_STRING_ATTR);
        returnAttrs.add(AMRecordDataEntry.OPERATION);
        returnAttrs.add(AMRecordDataEntry.SERVICE);
        returnAttrs.add(AMRecordDataEntry.STATE);

        numSubOrgAttrs = new LinkedHashSet<String>();
        numSubOrgAttrs.add(NUM_SUB_ORD_ATTR);

        serverAttrs = new LinkedHashSet<String>();
        serverAttrs.add("cn");
        serverAttrs.add("jmxPort");
        serverAttrs.add("adminPort");
        serverAttrs.add("ldapPort");
        serverAttrs.add("replPort");

    }

    /**
     * OpenDJ Persistent Store Constructor
     * Bootstrap this Service Implementation.
     *
     * @throws StoreException
     */
    @SuppressWarnings("CallToThreadStartDuringObjectConstruction")
    public OpenDJPersistentStore()
            throws StoreException {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                internalShutdown();
            }
        });

        storeThread = new Thread(this);
        storeThread.setName(ID);
        storeThread.start();
        initializeOpenDJ();
        icConn = InternalClientConnection.getRootConnection();
        Log.logger.log(Level.FINE, DB_DJ_STR_OK.get().toString());
    }

    private boolean replicationEnabled() {
        boolean multipleServers = false;

        try {
            multipleServers = getServers().size() > 1;
        } catch (StoreException se) {
            Log.logger.log(Level.SEVERE, DB_DJ_SVR_CNT.get().toString(), se);
        }

        return multipleServers;
    }

    private void initializeOpenDJ()
            throws StoreException {
        try {
            //EmbeddedOpenDJ.startServer(OpenDJConfig.getOdjRoot());
        } catch (Exception ex) {
            Log.logger.log(Level.SEVERE, DB_DJ_NO_START.get().toString(), ex);
            throw new StoreException(DB_DJ_NO_START.get().toString(), ex);
        }
    }

    /**
     * Service Thread Run Process Loop.
     */
    @SuppressWarnings("SleepWhileInLoop")
    @Override
    public void run() {
        while (!shutdown) {
            try {
                // Process any Deferred Operations
                processDeferredAMSessionRepositoryOperations();
                Thread.sleep(sleepInterval);
                long curTime = System.currentTimeMillis() / 1000;
                deleteExpired(curTime);
            } catch (InterruptedException ie) {
                Log.logger.log(Level.WARNING, DB_THD_INT.get().toString(), ie);
            } catch (StoreException se) {
                Log.logger.log(Level.WARNING, DB_STR_EX.get().toString(), se);
            }
        }
    }

    public boolean addElement(Object obj) {
        return false;
    }

    public boolean removeElement(Object obj) {
        return false;
    }

    public boolean isEmpty() {
        return true;
    }

    /**
     * Saves session state to the repository If it is a new session (version ==
     * 0) it inserts new record(s) to the repository. For an existing session it
     * updates repository record instead while checking that versions in the
     * InternalSession and in the record match In case of version mismatch or
     * missing record IllegalArgumentException will be thrown
     *
     * @param is reference to <code>InternalSession</code> object being saved.
     * @throws Exception if anything goes wrong.
     */
    @Override
    public void save(InternalSession is) throws Exception {
        saveImmediate(is);
    }

    private void saveImmediate(InternalSession is) throws Exception {
        try {
            SessionID sid = is.getID();
            String key = SessionUtils.getEncryptedStorageKey(sid);
            byte[] blob = SessionUtils.encode(is);
            long expirationTime = is.getExpirationTime() + gracePeriod;
            String uuid = caseSensitiveUUID ? is.getUUID() : is.getUUID().toLowerCase();
            if (debug.messageEnabled()) {
                debug.message("OpenDJPersistentStore.save(): " +
                        "session size=" + blob.length + " bytes");
            }

            FAMRecord famRec = new FAMRecord (
                    SESSION, FAMRecord.WRITE, key, expirationTime, uuid,
                    is.getState(), sid.toString(), blob);

            writeImmediate(famRec);

           } catch (Exception e) {
            debug.error("OpenDJPersistenceStore.save(): failed "
                    + "to save Session", e);
        }
    }

    /**
     * Takes an AMRecord and writes this to the store
     *
     * @param amRootEntity The record object to store
     * @throws com.iplanet.dpro.session.exceptions.StoreException
     *
     */
    @Override
    public void write(AMRootEntity amRootEntity) throws StoreException {
        //amSessionRepositoryEventConcurrentLinkedQueue.
        writeImmediate(amRootEntity);
    }

    /**
     * Takes an AMRecord and writes this to the store
     *
     * @param record The record object to store
     * @throws com.iplanet.dpro.session.exceptions.StoreException
     *
     */
    private void writeImmediate(AMRootEntity record)
            throws StoreException {
        boolean found = false;
        StringBuilder baseDN = new StringBuilder();
        baseDN.append(Constants.AMRECORD_NAMING_ATTR).append(Constants.EQUALS);
        baseDN.append((record).getPrimaryKey()).append(Constants.COMMA);
        baseDN.append(Constants.BASE_DN).append(Constants.COMMA).append(OpenDJConfig.getSessionDBSuffix());

        try {
            InternalSearchOperation iso = icConn.processSearch(baseDN.toString(),
                    SearchScope.SINGLE_LEVEL, DereferencePolicy.NEVER_DEREF_ALIASES,
                    0, 0, false, Constants.FAMRECORD_FILTER, returnAttrs);
            ResultCode resultCode = iso.getResultCode();

            if (resultCode == ResultCode.SUCCESS) {
                final LocalizableMessage message = DB_ENT_P.get(baseDN);
                Log.logger.log(Level.FINE, message.toString());
                found = true;
            } else if (resultCode == ResultCode.NO_SUCH_OBJECT) {
                final LocalizableMessage message = DB_ENT_NOT_P.get(baseDN);
                Log.logger.log(Level.FINE, message.toString());
            } else {
                final LocalizableMessage message = DB_ENT_ACC_FAIL.get(baseDN, resultCode.toString());
                Log.logger.log(Level.WARNING, message.toString());
                throw new StoreException(message.toString());
            }
        } catch (DirectoryException dex) {
            final LocalizableMessage message = DB_ENT_ACC_FAIL2.get(baseDN);
            Log.logger.log(Level.WARNING, message.toString(), dex);
            throw new StoreException(message.toString(), dex);
        }

        if (found) {
            updateImmediate(record);
        } else {
            storeImmediate(record);
        }
    }

    private void storeImmediate(AMRootEntity record)
            throws StoreException {
        AMRecordDataEntry entry = new AMRecordDataEntry(record);
        List<RawAttribute> attrList = entry.getAttrList();
        StringBuilder dn = new StringBuilder();
        dn.append(AMRecordDataEntry.PRI_KEY).append(Constants.EQUALS).append(record.getPrimaryKey());
        dn.append(Constants.COMMA).append(Constants.BASE_DN);
        dn.append(Constants.COMMA).append(OpenDJConfig.getSessionDBSuffix());
        attrList.addAll(AMRecordDataEntry.getObjectClasses());
        AddOperation ao = icConn.processAdd(dn.toString(), attrList);
        ResultCode resultCode = ao.getResultCode();

        if (resultCode == ResultCode.SUCCESS) {
            final LocalizableMessage message = DB_SVR_CREATE.get(dn);
            Log.logger.log(Level.FINE, message.toString());
        } else if (resultCode == ResultCode.ENTRY_ALREADY_EXISTS) {
            final LocalizableMessage message = DB_SVR_CRE_FAIL.get(dn);
            Log.logger.log(Level.WARNING, message.toString());
        } else {
            final LocalizableMessage message = DB_SVR_CRE_FAIL2.get(dn, resultCode.toString());
            Log.logger.log(Level.WARNING, message.toString());
            throw new StoreException(message.toString());
        }
    }

    private void updateImmediate(AMRootEntity record)
            throws StoreException {
        List<RawModification> modList = createModificationList(record);
        StringBuilder dn = new StringBuilder();
        dn.append(AMRecordDataEntry.PRI_KEY).append(Constants.EQUALS).append(record.getPrimaryKey());
        dn.append(Constants.COMMA).append(Constants.BASE_DN);
        dn.append(Constants.COMMA).append(OpenDJConfig.getSessionDBSuffix());

        ModifyOperation mo = icConn.processModify(dn.toString(), modList);
        ResultCode resultCode = mo.getResultCode();

        if (resultCode == ResultCode.SUCCESS) {
            final LocalizableMessage message = DB_SVR_MOD.get(dn);
            Log.logger.log(Level.FINE, message.toString());
        } else {
            final LocalizableMessage message = DB_SVR_MOD_FAIL.get(dn, resultCode.toString());
            Log.logger.log(Level.WARNING, message.toString());
            throw new StoreException(message.toString());
        }
    }


    private List<RawModification> createModificationList(AMRootEntity record)
            throws StoreException {
        List<RawModification> mods = new ArrayList<RawModification>();
        AMRecordDataEntry entry = new AMRecordDataEntry(record);
        List<RawAttribute> attrList = entry.getAttrList();

        for (RawAttribute attr : attrList) {
            RawModification mod = new LDAPModification(ModificationType.REPLACE, attr);
            mods.add(mod);
        }

        return mods;
    }

    /**
     * Deletes a record from the store.
     *
     * @param id The id of the record to delete from the store
     * @throws com.iplanet.dpro.session.exceptions.StoreException
     *
     * @throws com.iplanet.dpro.session.exceptions.NotFoundException
     *
     */
    @Override
    public void delete(String id) throws StoreException, NotFoundException {
        deleteImmediate(id);
    }

    private void deleteImmediate(String id)
            throws StoreException, NotFoundException {
        StringBuilder dn = new StringBuilder();
        dn.append(AMRecordDataEntry.PRI_KEY).append(Constants.EQUALS).append(id);
        dn.append(Constants.COMMA).append(Constants.BASE_DN);
        dn.append(Constants.COMMA).append(OpenDJConfig.getSessionDBSuffix());
        DeleteOperation dop = icConn.processDelete(dn.toString());
        ResultCode resultCode = dop.getResultCode();

        if (resultCode != ResultCode.SUCCESS) {
            final LocalizableMessage message = DB_ENT_DEL_FAIL.get(dn);
            Log.logger.log(Level.WARNING, message.toString());
        }
    }

    public void deleteExpired() throws Exception {
        // TODO -- Use a Default Expiration Period.
    }

    public void deleteExpired(long expDate)
            throws StoreException {
        try {
            StringBuilder baseDN = new StringBuilder();
            StringBuilder filter = new StringBuilder();
            filter.append(EXPDATE_FILTER_PRE).append(expDate).append(EXPDATE_FILTER_POST);
            baseDN.append(Constants.BASE_DN).append(Constants.COMMA).append(OpenDJConfig.getSessionDBSuffix());
            InternalSearchOperation iso = icConn.processSearch(baseDN.toString(),
                    SearchScope.SINGLE_LEVEL, DereferencePolicy.NEVER_DEREF_ALIASES,
                    0, 0, false, filter.toString(), returnAttrs);
            ResultCode resultCode = iso.getResultCode();

            if (resultCode == ResultCode.SUCCESS) {
                LinkedList<SearchResultEntry> searchResult = iso.getSearchEntries();

                if (!searchResult.isEmpty()) {
                    for (SearchResultEntry entry : searchResult) {
                        List<Attribute> attributes = entry.getAttributes();

                        Map<String, Set<String>> results =
                                EmbeddedSearchResultIterator.convertLDAPAttributeSetToMap(attributes);

                        Set<String> value = results.get(AMRecordDataEntry.PRI_KEY);

                        if (value != null && !value.isEmpty()) {
                            for (String v : value) {
                                try {
                                    deleteImmediate(v);
                                } catch (NotFoundException nfe) {
                                    final LocalizableMessage message = DB_ENT_NOT_FOUND.get(v);
                                    Log.logger.log(Level.WARNING, message.toString());
                                }
                            }
                        }
                    }
                }
            } else if (resultCode == ResultCode.NO_SUCH_OBJECT) {
                final LocalizableMessage message = DB_ENT_NOT_P.get(OpenDJConfig.getSessionDBSuffix());
                Log.logger.log(Level.FINE, message.toString());
            } else {
                final LocalizableMessage message = DB_ENT_ACC_FAIL.get(OpenDJConfig.getSessionDBSuffix(), resultCode.toString());
                Log.logger.log(Level.WARNING, message.toString());
                throw new StoreException(message.toString());
            }
        } catch (DirectoryException dex) {
            final LocalizableMessage message = DB_ENT_ACC_FAIL2.get(OpenDJConfig.getSessionDBSuffix());
            Log.logger.log(Level.WARNING, message.toString(), dex);
            throw new StoreException(message.toString(), dex);
        } catch (Exception ex) {
            if (!shutdown) {
                Log.logger.log(Level.WARNING, DB_ENT_EXP_FAIL.get().toString(), ex);
            } else {
                Log.logger.log(Level.FINEST, DB_ENT_EXP_FAIL.get().toString(), ex);
            }
        }
    }

    /**
     * Deletes session record from the repository.
     *
     * @param sid session ID.
     * @throws Exception if anything goes wrong.
     */
    @Override
    public void delete(SessionID sid) throws Exception {
        deleteImmediate(sid);
    }

    private void deleteImmediate(SessionID sid) throws Exception {
        // TODO
    }

    @Override
    public AMRootEntity read(String id)
            throws NotFoundException, StoreException {
        StringBuilder baseDN = new StringBuilder();

        try {
            baseDN.append(Constants.AMRECORD_NAMING_ATTR).append(Constants.EQUALS);
            baseDN.append(id).append(Constants.COMMA).append(Constants.BASE_DN);
            baseDN.append(Constants.COMMA).append(OpenDJConfig.getSessionDBSuffix());
            InternalSearchOperation iso = icConn.processSearch(baseDN.toString(),
                    SearchScope.BASE_OBJECT, DereferencePolicy.NEVER_DEREF_ALIASES,
                0, 0, false, Constants.FAMRECORD_FILTER , returnAttrs);
            ResultCode resultCode = iso.getResultCode();

            if (resultCode == ResultCode.SUCCESS) {
                LinkedList searchResult = iso.getSearchEntries();

                if (!searchResult.isEmpty()) {
                    SearchResultEntry entry =
                            (SearchResultEntry) searchResult.get(0);
                    List<Attribute> attributes = entry.getAttributes();

                    Map<String, Set<String>> results =
                            EmbeddedSearchResultIterator.convertLDAPAttributeSetToMap(attributes);
                    AMRecordDataEntry dataEntry = new AMRecordDataEntry("pkey=" + id + "," + baseDN, AMRecord.READ, results);
                    return dataEntry.getAMRecord();
                } else {
                    return null;
                }
            } else if (resultCode == ResultCode.NO_SUCH_OBJECT) {
                final LocalizableMessage message = DB_ENT_NOT_P.get(baseDN);
                Log.logger.log(Level.FINE, message.toString());

                return null;
            } else {
                Object[] params = {baseDN, resultCode};
                final LocalizableMessage message = DB_ENT_ACC_FAIL.get(baseDN, resultCode.toString());
                Log.logger.log(Level.WARNING, message.toString());
                throw new StoreException(message.toString());
            }
        } catch (DirectoryException dex) {
            final LocalizableMessage message = DB_ENT_ACC_FAIL2.get(baseDN);
            Log.logger.log(Level.WARNING, message.toString(), dex);
            throw new StoreException(message.toString(), dex);
        }
    }

    @Override
    public Set<String> readWithSecKey(String id)
            throws StoreException, NotFoundException {
        try {
            StringBuilder baseDN = new StringBuilder();
            StringBuilder filter = new StringBuilder();
            filter.append(SKEY_FILTER_PRE).append(id).append(SKEY_FILTER_POST);
            baseDN.append(Constants.BASE_DN).append(Constants.COMMA).append(OpenDJConfig.getSessionDBSuffix());
            InternalSearchOperation iso = icConn.processSearch(baseDN.toString(),
                    SearchScope.SINGLE_LEVEL, DereferencePolicy.NEVER_DEREF_ALIASES,
                    0, 0, false, filter.toString(), returnAttrs);
            ResultCode resultCode = iso.getResultCode();

            if (resultCode == ResultCode.SUCCESS) {
                LinkedList<SearchResultEntry> searchResult = iso.getSearchEntries();

                if (!searchResult.isEmpty()) {
                    Set<String> result = new HashSet<String>();

                    for (SearchResultEntry entry : searchResult) {
                        List<Attribute> attributes = entry.getAttributes();
                        Map<String, Set<String>> results =
                                EmbeddedSearchResultIterator.convertLDAPAttributeSetToMap(attributes);

                        Set<String> value = results.get(AMRecordDataEntry.DATA);

                        if (value != null && !value.isEmpty()) {
                            for (String v : value) {
                                result.add(v);
                            }
                        }
                    }

                    final LocalizableMessage message = DB_R_SEC_KEY_OK.get(id, Integer.toString(result.size()));
                    Log.logger.log(Level.FINE, message.toString());

                    return result;
                } else {
                    return null;
                }
            } else if (resultCode == ResultCode.NO_SUCH_OBJECT) {
                final LocalizableMessage message = DB_ENT_NOT_P.get(OpenDJConfig.getSessionDBSuffix());
                Log.logger.log(Level.FINE, message.toString());

                return null;
            } else {
                final LocalizableMessage message = DB_ENT_ACC_FAIL.get(OpenDJConfig.getSessionDBSuffix(), resultCode.toString());
                Log.logger.log(Level.WARNING, message.toString());
                throw new StoreException(message.toString());
            }
        } catch (DirectoryException dex) {
            final LocalizableMessage message = DB_ENT_ACC_FAIL2.get(OpenDJConfig.getSessionDBSuffix());
            Log.logger.log(Level.WARNING, message.toString(), dex);
            throw new StoreException(message.toString(), dex);
        }
    }

    @Override
    public Map<String, Long> getRecordCount(String id)
            throws StoreException {
        try {
            StringBuilder baseDN = new StringBuilder();
            StringBuilder filter = new StringBuilder();
            filter.append(SKEY_FILTER_PRE).append(id).append(SKEY_FILTER_POST);
            baseDN.append(Constants.BASE_DN).append(Constants.COMMA).append(OpenDJConfig.getSessionDBSuffix());
            InternalSearchOperation iso = icConn.processSearch(baseDN.toString(),
                    SearchScope.SINGLE_LEVEL, DereferencePolicy.NEVER_DEREF_ALIASES,
                    0, 0, false, filter.toString(), returnAttrs);
            ResultCode resultCode = iso.getResultCode();

            if (resultCode == ResultCode.SUCCESS) {
                LinkedList<SearchResultEntry> searchResult = iso.getSearchEntries();

                if (!searchResult.isEmpty()) {
                    Map<String, Long> result = new HashMap<String, Long>();

                    for (SearchResultEntry entry : searchResult) {
                        List<Attribute> attributes = entry.getAttributes();
                        Map<String, Set<String>> results =
                                EmbeddedSearchResultIterator.convertLDAPAttributeSetToMap(attributes);

                        String key = "";
                        Long expDate = new Long(0);

                        Set<String> value = results.get(AMRecordDataEntry.AUX_DATA);

                        if (value != null && !value.isEmpty()) {
                            for (String v : value) {
                                key = v;
                            }
                        }

                        value = results.get(AMRecordDataEntry.EXP_DATE);

                        if (value != null && !value.isEmpty()) {
                            for (String v : value) {
                                expDate = AMRecordDataEntry.toAMDateFormat(v);
                            }
                        }

                        result.put(key, expDate);
                    }

                    final LocalizableMessage message = DB_GET_REC_CNT_OK.get(id, Integer.toString(result.size()));
                    Log.logger.log(Level.FINE, message.toString());

                    return result;
                } else {
                    return null;
                }
            } else if (resultCode == ResultCode.NO_SUCH_OBJECT) {
                final LocalizableMessage message = DB_ENT_NOT_P.get(OpenDJConfig.getSessionDBSuffix());
                Log.logger.log(Level.FINE, message.toString());

                return null;
            } else {
                final LocalizableMessage message = DB_ENT_ACC_FAIL.get(OpenDJConfig.getSessionDBSuffix(), resultCode.toString());
                Log.logger.log(Level.WARNING, message.toString());
                throw new StoreException(message.toString());
            }
        } catch (DirectoryException dex) {
            final LocalizableMessage message = DB_ENT_ACC_FAIL2.get(OpenDJConfig.getSessionDBSuffix());
            Log.logger.log(Level.WARNING, message.toString(), dex);
            throw new StoreException(message.toString(), dex);
        }
    }

    @Override
    public void shutdown() {
        internalShutdown();
        Log.logger.log(Level.FINE, DB_AM_SHUT.get().toString());
    }

    protected void internalShutdown() {
        shutdown = true;
        Log.logger.log(Level.FINE, DB_AM_INT_SHUT.get().toString());

        try {
            //EmbeddedOpenDJ.shutdownServer();
        } catch (Exception ex) {
            Log.logger.log(Level.WARNING, DB_AM_SHUT_FAIL.get().toString(), ex);
        }
    }

    @Override
    public DBStatistics getDBStatistics() {
        DBStatistics stats = DBStatistics.getInstance();

        try {
            stats.setNumRecords(getNumSubordinates());
        } catch (StoreException se) {
            final LocalizableMessage message = DB_STATS_FAIL.get(se.getMessage());
            Log.logger.log(Level.WARNING, message.toString());
            stats.setNumRecords(-1);
        }

        return stats;
    }

    protected int getNumSubordinates()
            throws StoreException {
        int recordCount = -1;
        StringBuilder baseDN = new StringBuilder();
        baseDN.append(Constants.BASE_DN).append(Constants.COMMA).append(OpenDJConfig.getSessionDBSuffix());

        try {
            InternalSearchOperation iso = icConn.processSearch(baseDN.toString(),
                    SearchScope.BASE_OBJECT, DereferencePolicy.NEVER_DEREF_ALIASES,
                    0, 0, false, ALL_ATTRS, numSubOrgAttrs);
            ResultCode resultCode = iso.getResultCode();

            if (resultCode == ResultCode.SUCCESS) {
                LinkedList<SearchResultEntry> searchResult = iso.getSearchEntries();

                if (!searchResult.isEmpty()) {
                    for (SearchResultEntry entry : searchResult) {
                        List<Attribute> attributes = entry.getAttributes();

                        for (Attribute attr : attributes) {
                            if (attr.isVirtual() && attr.getName().equals(NUM_SUB_ORD_ATTR)) {
                                Iterator<AttributeValue> values = attr.iterator();

                                while (values.hasNext()) {
                                    AttributeValue value = values.next();

                                    try {
                                        recordCount = Integer.parseInt(value.toString());
                                    } catch (NumberFormatException nfe) {
                                        final LocalizableMessage message = DB_STATS_NFS.get(nfe.getMessage());
                                        Log.logger.log(Level.INFO, message.toString());
                                        throw new StoreException(message.toString());
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (resultCode == ResultCode.NO_SUCH_OBJECT) {
                final LocalizableMessage message = DB_ENT_NOT_P.get(OpenDJConfig.getSessionDBSuffix());
                Log.logger.log(Level.FINE, message.toString());
                throw new StoreException(message.toString());
            } else {
                final LocalizableMessage message = DB_ENT_ACC_FAIL.get(OpenDJConfig.getSessionDBSuffix(), resultCode.toString());
                Log.logger.log(Level.WARNING, message.toString());
                throw new StoreException(message.toString());
            }
        } catch (DirectoryException dex) {
            final LocalizableMessage message = DB_ENT_ACC_FAIL2.get(OpenDJConfig.getSessionDBSuffix());
            Log.logger.log(Level.WARNING, message.toString(), dex);
            throw new StoreException(message.toString(), dex);
        }

        return recordCount;
    }

    /**
     * Retrieve a persisted Internal Session.
     *
     * @param sid session ID
     * @return
     * @throws Exception
     */
    @Override
    public InternalSession retrieve(SessionID sid) throws Exception {
        try {
            String key = SessionUtils.getEncryptedStorageKey(sid);

            FAMRecord famRecord = (FAMRecord)this.read(key);

            InternalSession is = null;
            if(famRecord != null) {
                byte[] blob = famRecord.getBlob();
                is = (InternalSession) SessionUtils.decode(blob);
            }

            /*
             * ret.put(SESSIONID, message.getString(SESSIONID)); ret.put(DATA,
             * message.getString(DATA));
             */
            return is;

        } catch (Exception e) {
            debug.message("OpenDJPersistentStore.retrieve(): failed retrieving "
                    + "session", e);
            return null;
        }
    }

    @Override
    public Map getSessionsByUUID(String uuid) throws Exception {
        return null;  // TODO
    }

    @Override
    public long getRunPeriod() {
        return 0;  // TODO
    }

    public static Set<AMSessionDBOpenDJServer> getServers()
            throws StoreException {
        InternalClientConnection icConn = InternalClientConnection.getRootConnection();
        Set<AMSessionDBOpenDJServer> serverList = new HashSet<AMSessionDBOpenDJServer>();
        StringBuilder baseDn = new StringBuilder();
        baseDn.append(Constants.HOSTS_BASE_DN);
        baseDn.append(Constants.COMMA).append(OpenDJConfig.getSessionDBSuffix());

        try {
            InternalSearchOperation iso = icConn.processSearch(baseDn.toString(),
                    SearchScope.SINGLE_LEVEL, DereferencePolicy.NEVER_DEREF_ALIASES,
                    0, 0, false, "objectclass=*" , serverAttrs);
            ResultCode resultCode = iso.getResultCode();

            if (resultCode == ResultCode.SUCCESS) {
                LinkedList<SearchResultEntry> searchResult = iso.getSearchEntries();

                if (!searchResult.isEmpty()) {
                    for (SearchResultEntry entry : searchResult) {
                        List<Attribute> attributes = entry.getAttributes();
                        AMSessionDBOpenDJServer server = new AMSessionDBOpenDJServer();

                        for (Attribute attribute : attributes) {
                            if (attribute.getName().equals("cn")) {
                                server.setHostName(getFQDN(attribute.iterator().next().getValue().toString()));
                            } else if (attribute.getName().equals("adminPort")) {
                                server.setAdminPort(attribute.iterator().next().getValue().toString());
                            } else if (attribute.getName().equals("jmxPort")) {
                                server.setJmxPort(attribute.iterator().next().getValue().toString());
                            } else if (attribute.getName().equals("ldapPort")) {
                                server.setLdapPort(attribute.iterator().next().getValue().toString());
                            } else if (attribute.getName().equals("replPort")) {
                                server.setReplPort(attribute.iterator().next().getValue().toString());
                            } else {
                                final LocalizableMessage message = DB_UNK_ATTR.get(attribute.getName());
                                Log.logger.log(Level.WARNING, message.toString());
                            }
                        }

                        serverList.add(server);
                    }
                }
            } else if (resultCode == ResultCode.NO_SUCH_OBJECT) {
                final LocalizableMessage message = DB_ENT_NOT_P.get(baseDn);
                Log.logger.log(Level.FINE, message.toString());

                return null;
            } else {
                final LocalizableMessage message = DB_ENT_ACC_FAIL.get(baseDn, resultCode.toString());
                Log.logger.log(Level.WARNING, message.toString());
                throw new StoreException(message.toString());
            }
        } catch (DirectoryException dex) {
            Object[] error = { baseDn };
            final LocalizableMessage message = DB_ENT_ACC_FAIL2.get(baseDn);
            Log.logger.log(Level.WARNING, message.toString());
            throw new StoreException(message.toString(), dex);
        }

        return serverList;
    }

    private static String getFQDN(String urlHost) {
        URL url = null;

        try {
            url = new URL(urlHost);
        } catch (MalformedURLException mue) {
            final LocalizableMessage message = DB_MAL_URL.get(urlHost);
            Log.logger.log(Level.WARNING, message.toString());
            return urlHost;
        }

        return url.getHost();
    }

    /**
     * Process any and all deferred AM Session Repository Operations.
     */
    private synchronized void processDeferredAMSessionRepositoryOperations() {
        debug.error("Begin Processing Deferred AMSession Repository Operations.");
        int count = 0;
        ConcurrentLinkedQueue<AMSessionRepositoryDeferredOperation>
                tobeRemoved
                = new ConcurrentLinkedQueue<AMSessionRepositoryDeferredOperation>();
        for (AMSessionRepositoryDeferredOperation amSessionRepositoryDeferredOperation :
                amSessionRepositoryDeferredOperationConcurrentLinkedQueue) {
           count++;
            // TODO -- Build out Implementation to invoke Session Repository Events.

           // Place Entry to Collection to be removed from Queue.
           tobeRemoved.add(amSessionRepositoryDeferredOperation);
        }
        debug.error("End of Processing Deferred AMSession Repository Operations.");
        debug.error("Performed " + count + " Deferred Operations.");
        amSessionRepositoryDeferredOperationConcurrentLinkedQueue.removeAll(tobeRemoved);
    }


}
