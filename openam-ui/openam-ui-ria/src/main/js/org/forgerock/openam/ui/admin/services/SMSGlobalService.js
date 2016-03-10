/**
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2015-2016 ForgeRock AS.
 */

define("org/forgerock/openam/ui/admin/services/SMSGlobalService", [
    "jquery",
    "underscore",
    "org/forgerock/commons/ui/common/main/AbstractDelegate",
    "org/forgerock/commons/ui/common/util/Constants",
    "org/forgerock/openam/ui/admin/services/SMSServiceUtils",
    "org/forgerock/openam/ui/common/util/RealmHelper"
], function ($, _, AbstractDelegate, Constants, SMSServiceUtils, RealmHelper) {
    /**
     * @exports org/forgerock/openam/ui/admin/services/SMSGlobalService
     */
    var obj = new AbstractDelegate(Constants.host + "/" + Constants.context + "/json/global-config/"),
        schemaWithValues = function (url) {
            return $.when(
                obj.serviceCall({
                    url: url + "?_action=schema",
                    headers: { "Accept-API-Version": "protocol=1.0,resource=1.0" },
                    type: "POST"
                }),
                obj.serviceCall({
                    url: url,
                    headers: { "Accept-API-Version": "protocol=1.0,resource=1.0" }
                })
            ).then(function (schemaData, valuesData) {
                return {
                    schema: SMSServiceUtils.sanitizeSchema(schemaData[0]),
                    values: valuesData[0]
                };
            });
        },
        schemaWithDefaults = function (url) {
            return $.when(
                obj.serviceCall({
                    url: url + "?_action=schema",
                    headers: { "Accept-API-Version": "protocol=1.0,resource=1.0" },
                    type: "POST"
                }),
                obj.serviceCall({
                    url: url + "?_action=template",
                    headers: { "Accept-API-Version": "protocol=1.0,resource=1.0" },
                    type: "POST"
                })
            ).then(function (schemaData, templateData) {
                return {
                    schema: SMSServiceUtils.sanitizeSchema(schemaData[0]),
                    values: templateData[0]
                };
            });
        },
        getRealmPath = function (realm) {
            if (realm.parentPath === "/") {
                return realm.parentPath + realm.name;
            } else if (realm.parentPath) {
                return realm.parentPath + "/" + realm.name;
            } else {
                return "/";
            }
        };

    obj.authentication = {
        getAll: function () {
            return obj.serviceCall({
                url: "authentication/modules?_action=getAllTypes",
                headers: { "Accept-API-Version": "protocol=1.0,resource=1.0" },
                type: "POST"
            }).then((data) => data.result);
        }
    };

    obj.realms = {
        /**
         * Gets all realms.
         * @returns {Promise.<Object>} Service promise
         */
        all: function () {
            return obj.serviceCall({
                url: "realms?_queryFilter=true",
                headers: { "Accept-API-Version": "protocol=1.0,resource=1.0" }
            }).done(function (data) {
                data.result = _.each(data.result, function (realm) {
                    realm.path = getRealmPath(realm);
                }).sort(function (a, b) {
                    return a.path < b.path ? -1 : 1;
                });
            });
        },

        /**
         * Creates a realm.
         * @param  {Object} data Complete representation of realm
         * @returns {Promise} Service promise
         */
        create: function (data) {
            return obj.serviceCall({
                url: "realms?_action=create",
                headers: { "Accept-API-Version": "protocol=1.0,resource=1.0" },
                type: "POST",
                suppressEvents: true,
                data: JSON.stringify(data)
            });
        },

        /**
         * Gets a realm's schema together with it's values.
         * @param  {String} path Encoded realm path (must have leading slash). e.g. "/myrealm"
         * @returns {Promise.<Object>} Service promise
         */
        get: function (path) {
            return schemaWithValues("realms" + RealmHelper.encodeRealm(path));
        },

        /**
         * Gets a blank realm's schema together with it's values.
         * @returns {Promise.<Object>} Service promise
         */
        schema: function () {
            return schemaWithDefaults("realms");
        },

        /**
         * Removes a realm.
         * @param  {String} path Encoded realm path (must have leading slash). e.g. "/myrealm"
         * @returns {Promise} Service promise
         */
        remove: function (path) {
            return obj.serviceCall({
                url: "realms" + RealmHelper.encodeRealm(path),
                headers: { "Accept-API-Version": "protocol=1.0,resource=1.0" },
                type: "DELETE",
                suppressEvents: true
            });
        },

        /**
         * Updates a realm.
         * @param  {Object} data Complete representation of realm
         * @returns {Promise} Service promise
         */
        update: function (data) {
            return obj.serviceCall({
                url: "realms" + RealmHelper.encodeRealm(getRealmPath(data)),
                headers: { "Accept-API-Version": "protocol=1.0,resource=1.0" },
                type: "PUT",
                data: JSON.stringify(data),
                suppressEvents: true
            });
        }
    };

    obj.configuration = {
        mockSystem: [
            { "_id":"clientDetection", "name":"Client Detection" },
            { "_id":"logging", "name":"Logging" },
            { "_id":"monitoring", "name":"Monitoring" },
            { "_id":"naming", "name":"Naming" },
            { "_id":"platform", "name":"Platform" }
        ],
        mockConsole: [
            { "_id":"administration", "name":"Administration" },
            { "_id":"globalizationsettings", "name":"Globalization Settings" }
        ],
        services: {
            system: {
                getAll: function () {
                    return $.Deferred().resolve(obj.configuration.mockSystem);
                }
            },
            console: {
                getAll: function () {
                    return $.Deferred().resolve(obj.configuration.mockConsole);
                }
            }
            // getAll: function () {
            //     return obj.serviceCall({
            //         url: "services?_queryFilter=true",
            //         headers: { "Accept-API-Version": "protocol=1.0,resource=1.0" }
            //     });
            // }
        }
    };

    obj.scripts = {
        /**
         * Gets all script's contexts.
         * @returns {Promise.<Object>} Service promise
         */
        getAllContexts: function () {
            return obj.serviceCall({
                url: RealmHelper.decorateURLWithOverrideRealm("services/scripting/contexts?_queryFilter=true"),
                headers: { "Accept-API-Version": "protocol=1.0,resource=1.0" }
            });
        },

        /**
         * Gets a default global script's context.
         * @returns {Promise.<Object>} Service promise
         */
        getDefaultGlobalContext: function () {
            return obj.serviceCall({
                url: RealmHelper.decorateURLWithOverrideRealm("services/scripting"),
                headers: { "Accept-API-Version": "protocol=1.0,resource=1.0" }
            });
        },

        /**
         * Gets a script's schema.
         * @returns {Promise.<Object>} Service promise
         */
        getSchema: function () {
            return obj.serviceCall({
                url: RealmHelper.decorateURLWithOverrideRealm("services/scripting?_action=schema"),
                headers: { "Accept-API-Version": "protocol=1.0,resource=1.0" },
                type: "POST"
            });
        },

        /**
         * Gets a script context's schema.
         * @returns {Promise.<Object>} Service promise
         */
        getContextSchema: function () {
            return obj.serviceCall({
                url: RealmHelper.decorateURLWithOverrideRealm("services/scripting/contexts?_action=schema"),
                headers: { "Accept-API-Version": "protocol=1.0,resource=1.0" },
                type: "POST"
            });
        }
    };

    return obj;
});
