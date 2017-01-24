/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.server.security.enterprise.auth;

import org.junit.Test;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;

import org.neo4j.graphdb.Notification;
import org.neo4j.graphdb.Result;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;
import org.neo4j.kernel.impl.factory.GraphDatabaseFacade;
import org.neo4j.server.security.enterprise.configuration.SecuritySettings;


import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.neo4j.helpers.collection.MapUtil.map;
import static org.neo4j.helpers.collection.MapUtil.stringMap;
import static org.neo4j.server.security.enterprise.auth.plugin.api.PredefinedRoles.ADMIN;
import static org.neo4j.server.security.enterprise.auth.plugin.api.PredefinedRoles.ARCHITECT;
import static org.neo4j.server.security.enterprise.auth.plugin.api.PredefinedRoles.PUBLISHER;
import static org.neo4j.server.security.enterprise.auth.plugin.api.PredefinedRoles.READER;

public abstract class ConfiguredAuthScenariosInteractionTestBase<S> extends ProcedureInteractionTestBase<S>
{
    @Override
    public void setUp() throws Throwable
    {
        // tests are required to setup database with specific configs
    }

    @Test
    public void shouldNotAllowPublisherCreateNewTokens() throws Throwable
    {
        configuredSetup( defaultConfiguration() );
        assertFail( writeSubject, "CREATE (:MySpecialLabel)", TOKEN_CREATE_OPS_NOT_ALLOWED );
        assertFail( writeSubject, "MATCH (a:Node), (b:Node) WHERE a.number = 0 AND b.number = 1 " +
                "CREATE (a)-[:MySpecialRelationship]->(b)", TOKEN_CREATE_OPS_NOT_ALLOWED );
        assertFail( writeSubject, "CREATE (a) SET a.MySpecialProperty = 'a'", TOKEN_CREATE_OPS_NOT_ALLOWED );
    }

    @Test
    public void shouldAllowPublisherCreateNewTokensWhenConfigured() throws Throwable
    {
        configuredSetup( stringMap( SecuritySettings.allow_publisher_create_token.name(), "true" ) );
        assertEmpty( writeSubject, "CREATE (:MySpecialLabel)" );
        assertEmpty( writeSubject, "MATCH (a:Node), (b:Node) WHERE a.number = 0 AND b.number = 1 " +
                "CREATE (a)-[:MySpecialRelationship]->(b)" );
        assertEmpty( writeSubject, "CREATE (a) SET a.MySpecialProperty = 'a'" );
    }

    @Test
    public void shouldNotAllowPublisherCallCreateNewTokensProcedures() throws Throwable
    {
        configuredSetup( defaultConfiguration() );
        assertFail( writeSubject, "CALL db.createLabel('MySpecialLabel')", TOKEN_CREATE_OPS_NOT_ALLOWED );
        assertFail( writeSubject, "CALL db.createRelationshipType('MySpecialRelationship')", TOKEN_CREATE_OPS_NOT_ALLOWED );
        assertFail( writeSubject, "CALL db.createProperty('MySpecialProperty')", TOKEN_CREATE_OPS_NOT_ALLOWED );
    }

    @Test
    public void shouldAllowPublisherCallCreateNewTokensProceduresWhenConfigured() throws Throwable
    {
        configuredSetup( stringMap( SecuritySettings.allow_publisher_create_token.name(), "true" ) );
        assertEmpty( writeSubject, "CALL db.createLabel('MySpecialLabel')" );
        assertEmpty( writeSubject, "CALL db.createRelationshipType('MySpecialRelationship')" );
        assertEmpty( writeSubject, "CALL db.createProperty('MySpecialProperty')" );
    }

    @Test
    public void shouldAllowRoleCallCreateNewTokensProceduresWhenConfigured() throws Throwable
    {
        configuredSetup( stringMap( SecuritySettings.default_allowed.name(), "role1" ) );
        userManager.newRole( "role1", "noneSubject" );
        assertEmpty( noneSubject, "CALL db.createLabel('MySpecialLabel')" );
        assertEmpty( noneSubject, "CALL db.createRelationshipType('MySpecialRelationship')" );
        assertEmpty( noneSubject, "CALL db.createProperty('MySpecialProperty')" );
    }

    @Test
    public void shouldWarnWhenUsingNativeAndOtherProvider() throws Throwable
    {
        configuredSetup( stringMap( SecuritySettings.auth_providers.name(), "native ,LDAP" ) );
        assertSuccess( adminSubject, "CALL dbms.security.listUsers", r -> assertKeyIsMap( r, "username", "roles", userList ) );
        GraphDatabaseFacade localGraph = neo.getLocalGraph();
        InternalTransaction transaction = localGraph
                .beginTransaction( KernelTransaction.Type.explicit, StandardEnterpriseSecurityContext.AUTH_DISABLED );
        Result result =
                localGraph.execute( transaction, "EXPLAIN CALL dbms.security.listUsers", Collections.emptyMap() );
        assertThat(
                containsNotification( result, "Native user management procedures will not affect non-native users." ),
                equalTo( true ) );
        transaction.success();
        transaction.close();
    }

    @Test
    public void shouldNotWarnWhenUsingNativeAndOtherProvider() throws Throwable
    {
        configuredSetup( stringMap( SecuritySettings.auth_provider.name(), "native" ) );
        assertSuccess( adminSubject, "CALL dbms.security.listUsers", r -> assertKeyIsMap( r, "username", "roles", userList ) );

        GraphDatabaseFacade localGraph = neo.getLocalGraph();
        InternalTransaction transaction = localGraph
                .beginTransaction( KernelTransaction.Type.explicit, StandardEnterpriseSecurityContext.AUTH_DISABLED );
        Result result =
                localGraph.execute( transaction, "EXPLAIN CALL dbms.security.listUsers", Collections.emptyMap() );
        assertThat(
                containsNotification( result, "Native user management procedures will not affect non-native users." ),
                equalTo( false ) );
        transaction.success();
        transaction.close();
    }

    private Map<String, Object> userList = map(
            "adminSubject", listOf( ADMIN ),
            "readSubject", listOf( READER ),
            "schemaSubject", listOf( ARCHITECT ),
            "writeSubject", listOf( PUBLISHER ),
            "pwdSubject", listOf( ),
            "noneSubject", listOf( ),
            "neo4j", listOf( ADMIN )
    );

    private boolean containsNotification( Result result, String description )
    {
        Iterator<Notification> itr = result.getNotifications().iterator();
        boolean found = false;
        while ( itr.hasNext() )
        {
            found |= itr.next().getDescription().equals( description );
        }
        return found;
    }
}
