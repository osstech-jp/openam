/*
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
 * Copyright 2015 ForgeRock AS.
 */


define("org/forgerock/openam/ui/dashboard/views/OAuthTokensView", [
    "jquery",
    "underscore",
    "org/forgerock/commons/ui/common/main/AbstractView",
    "org/forgerock/openam/ui/dashboard/delegates/OAuthTokensDelegate"
], function ($, _, AbstractView, OAuthTokensDelegate) {
    var OAuthToken = AbstractView.extend({
        template: "templates/openam/dashboard/TokensTemplate.html",
        noBaseTemplate: true,
        element: "#myOAuthTokensSection",
        events: {
            "click a.deleteToken": "deleteToken"
        },

        render: function () {
            var self = this;

            OAuthTokensDelegate.getApplications().then(function (data) {
                self.data.applications = _.map(data.result, function (application) {
                    return {
                        id: application._id,
                        name: application.name,
                        scopes: _.values(application.scopes).join(", "),
                        expiryDateTime: (!application.expiryDateTime)
                            ? $.t("openam.oAuth2.tokens.neverExpires")
                            : new Date(application.expiryDateTime).toLocaleString()
                    };
                });
                self.parentRender(function () {
                    self.$el.find("[data-toggle=\"tooltip\"]").tooltip();
                });
            });
        },

        deleteToken: function (event) {
            event.preventDefault();
            var self = this;

            OAuthTokensDelegate.revokeApplication(event.currentTarget.id).then(function () {
                self.render();
            }, function () {
                console.error("Failed to revoke application");
            });
        }
    });

    return new OAuthToken();
});
