/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.cql3;

import org.junit.*;

import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.exceptions.InvalidRequestException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CDCStatementTest extends CQLTester
{
    @After
    public void after() throws Throwable
    {
        try { execute("ALTER KEYSPACE cdc_test DROP CDCLOG;"); }
        catch (Exception e) { }
        catch (Throwable t) { }
        execute("DROP KEYSPACE IF EXISTS cdc_test;");
    }

    @Test
    public void testKeyspaceSimpleWithCDC() throws Throwable
    {
        execute("CREATE KEYSPACE cdc_test WITH replication = { 'class' : 'SimpleStrategy', 'replication_factor' : 1} AND cdc_datacenters = {'dc_1'};");
    }

    @Test
    public void testKeyspaceNTSWithMatchingCDC() throws Throwable
    {
        execute("CREATE KEYSPACE cdc_test WITH replication = { 'class' : 'NetworkTopologyStrategy', 'dc_1' : 1, 'dc_2' : 3} AND cdc_datacenters = {'dc_1', 'dc_2'};");
    }

    @Test
    public void testCDCMisMatch() throws Throwable
    {
        try
        {
            execute("CREATE KEYSPACE cdc_test WITH replication = { 'class' : 'NetworkTopologyStrategy', 'dc_1' : 1, 'dc_2' : 3} AND cdc_datacenters = {'AAAdc_1', 'dc_2'};");
        }
        catch (ConfigurationException ce)
        {
            assertTrue(ce.getMessage().contains("CDC DataCenter for unknown DC added."));
        }
    }

    @Test
    public void testMultiCDCWithSimpleStrategyFail() throws Throwable
    {
        try
        {
            execute("CREATE KEYSPACE cdc_test WITH replication = { 'class' : 'SimpleStrategy', 'replication_factor' : 1} AND cdc_datacenters = {'dc_1', 'dc_2'};");
        }
        catch (ConfigurationException ce)
        {
            assertEquals(ce.getMessage(), "Single datacenter for replication needs 0 or 1 CDC datacenters specified.");
        }
    }

    @Test
    public void testAlterAddCDCSimple() throws Throwable
    {
        execute("CREATE KEYSPACE cdc_test WITH replication = { 'class' : 'SimpleStrategy', 'replication_factor' : 1};");
        execute("ALTER KEYSPACE cdc_test WITH cdc_datacenters = {'dc1'}");
    }

    @Test
    public void testAlterAddCDCNts() throws Throwable
    {
        execute("CREATE KEYSPACE cdc_test WITH replication = { 'class' : 'NetworkTopologyStrategy', 'dc1' : 1, 'dc2' : 2};");
        execute("ALTER KEYSPACE cdc_test WITH cdc_datacenters = {'dc1'}");
    }

    @Test
    public void testAlterAddBadCDCNts() throws Throwable
    {
        try
        {
            execute("CREATE KEYSPACE cdc_test WITH replication = { 'class' : 'NetworkTopologyStrategy', 'dc1' : 1, 'dc2' : 2};");
            execute("ALTER KEYSPACE cdc_test WITH cdc_datacenters = {'AAAdc1'}");
            Assert.fail("Expected ConfigurationException.");
        }
        catch (ConfigurationException ce)
        {
            assertTrue(ce.getMessage().contains("CDC DataCenter for unknown DC added."));
        }
    }

    @Test
    public void testDropWithCDCFails() throws Throwable
    {
        try
        {
            execute("CREATE KEYSPACE cdc_test WITH replication = { 'class' : 'NetworkTopologyStrategy', 'dc1' : 1, 'dc2' : 2} AND cdc_datacenters = {'dc1'};");
            execute("DROP KEYSPACE cdc_test;");
            Assert.fail("Expected ConfigurationException.");
        }
        catch (InvalidRequestException ire)
        {
            assertTrue(ire.getMessage().contains("Cannot drop keyspace with active CDC log"));
        }
    }

    @Test
    public void testDropCDCStatementSucceeds() throws Throwable
    {
        execute("CREATE KEYSPACE cdc_test WITH replication = { 'class' : 'NetworkTopologyStrategy', 'dc1' : 1, 'dc2' : 2};");
        execute("ALTER KEYSPACE cdc_test WITH cdc_datacenters = {'dc1'}");
        execute("ALTER KEYSPACE cdc_test DROP CDCLOG");
        execute("DROP KEYSPACE cdc_test;");
    }
}
