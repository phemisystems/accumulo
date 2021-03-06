/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.test.mapred;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;

import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.mapred.AccumuloInputFormat;
import org.apache.accumulo.core.client.mapred.RangeInputSplit;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.util.Pair;
import org.apache.accumulo.harness.AccumuloClusterHarness;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.lib.NullOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.log4j.Level;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class AccumuloInputFormatIT extends AccumuloClusterHarness {

  @BeforeClass
  public static void setupClass() {
    System.setProperty("hadoop.tmp.dir", System.getProperty("user.dir") + "/target/hadoop-tmp");
  }

  private static AssertionError e1 = null;
  private static AssertionError e2 = null;

  private static class MRTester extends Configured implements Tool {
    private static class TestMapper implements Mapper<Key,Value,Key,Value> {
      Key key = null;
      int count = 0;

      @Override
      public void map(Key k, Value v, OutputCollector<Key,Value> output, Reporter reporter) throws IOException {
        try {
          if (key != null)
            assertEquals(key.getRow().toString(), new String(v.get()));
          assertEquals(k.getRow(), new Text(String.format("%09x", count + 1)));
          assertEquals(new String(v.get()), String.format("%09x", count));
        } catch (AssertionError e) {
          e1 = e;
        }
        key = new Key(k);
        count++;
      }

      @Override
      public void configure(JobConf job) {}

      @Override
      public void close() throws IOException {
        try {
          assertEquals(100, count);
        } catch (AssertionError e) {
          e2 = e;
        }
      }

    }

    @Override
    public int run(String[] args) throws Exception {

      if (args.length != 1) {
        throw new IllegalArgumentException("Usage : " + MRTester.class.getName() + " <table>");
      }

      String table = args[0];

      JobConf job = new JobConf(getConf());
      job.setJarByClass(this.getClass());

      job.setInputFormat(AccumuloInputFormat.class);

      AccumuloInputFormat.setConnectorInfo(job, getAdminPrincipal(), getAdminToken());
      AccumuloInputFormat.setInputTableName(job, table);
      AccumuloInputFormat.setZooKeeperInstance(job, getCluster().getClientConfig());

      job.setMapperClass(TestMapper.class);
      job.setMapOutputKeyClass(Key.class);
      job.setMapOutputValueClass(Value.class);
      job.setOutputFormat(NullOutputFormat.class);

      job.setNumReduceTasks(0);

      return JobClient.runJob(job).isSuccessful() ? 0 : 1;
    }

    public static void main(String... args) throws Exception {
      Configuration conf = new Configuration();
      conf.set("mapreduce.cluster.local.dir", new File(System.getProperty("user.dir"), "target/mapreduce-tmp").getAbsolutePath());
      assertEquals(0, ToolRunner.run(conf, new MRTester(), args));
    }
  }

  @Test
  public void testMap() throws Exception {
    String table = getUniqueNames(1)[0];
    Connector c = getConnector();
    c.tableOperations().create(table);
    BatchWriter bw = c.createBatchWriter(table, new BatchWriterConfig());
    for (int i = 0; i < 100; i++) {
      Mutation m = new Mutation(new Text(String.format("%09x", i + 1)));
      m.put(new Text(), new Text(), new Value(String.format("%09x", i).getBytes()));
      bw.addMutation(m);
    }
    bw.close();

    MRTester.main(table);
    assertNull(e1);
    assertNull(e2);
  }

  @Test
  public void testCorrectRangeInputSplits() throws Exception {
    JobConf job = new JobConf();

    String table = getUniqueNames(1)[0];
    Authorizations auths = new Authorizations("foo");
    Collection<Pair<Text,Text>> fetchColumns = Collections.singleton(new Pair<Text,Text>(new Text("foo"), new Text("bar")));
    boolean isolated = true, localIters = true;
    Level level = Level.WARN;

    Connector connector = getConnector();
    connector.tableOperations().create(table);

    AccumuloInputFormat.setConnectorInfo(job, getAdminPrincipal(), getAdminToken());
    AccumuloInputFormat.setInputTableName(job, table);
    AccumuloInputFormat.setScanAuthorizations(job, auths);
    AccumuloInputFormat.setZooKeeperInstance(job, getCluster().getClientConfig());
    AccumuloInputFormat.setScanIsolation(job, isolated);
    AccumuloInputFormat.setLocalIterators(job, localIters);
    AccumuloInputFormat.fetchColumns(job, fetchColumns);
    AccumuloInputFormat.setLogLevel(job, level);

    AccumuloInputFormat aif = new AccumuloInputFormat();

    InputSplit[] splits = aif.getSplits(job, 1);

    Assert.assertEquals(1, splits.length);

    InputSplit split = splits[0];

    Assert.assertEquals(RangeInputSplit.class, split.getClass());

    RangeInputSplit risplit = (RangeInputSplit) split;

    Assert.assertEquals(getAdminPrincipal(), risplit.getPrincipal());
    Assert.assertEquals(table, risplit.getTableName());
    Assert.assertEquals(getAdminToken(), risplit.getToken());
    Assert.assertEquals(auths, risplit.getAuths());
    Assert.assertEquals(getConnector().getInstance().getInstanceName(), risplit.getInstanceName());
    Assert.assertEquals(isolated, risplit.isIsolatedScan());
    Assert.assertEquals(localIters, risplit.usesLocalIterators());
    Assert.assertEquals(fetchColumns, risplit.getFetchedColumns());
    Assert.assertEquals(level, risplit.getLogLevel());
  }
}
