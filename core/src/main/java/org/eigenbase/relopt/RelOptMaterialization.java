/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.eigenbase.relopt;

import java.util.List;

import org.eigenbase.rel.*;
import org.eigenbase.rel.metadata.DefaultRelMetadataProvider;
import org.eigenbase.rel.rules.AggregateFilterTransposeRule;
import org.eigenbase.rel.rules.AggregateProjectMergeRule;
import org.eigenbase.rel.rules.MergeProjectRule;
import org.eigenbase.rel.rules.PullUpProjectsAboveJoinRule;
import org.eigenbase.rel.rules.PushFilterPastJoinRule;
import org.eigenbase.rel.rules.PushProjectPastFilterRule;
import org.eigenbase.rex.RexNode;
import org.eigenbase.rex.RexUtil;
import org.eigenbase.sql.SqlExplainLevel;
import org.eigenbase.util.Util;
import org.eigenbase.util.mapping.Mappings;

import net.hydromatic.optiq.Table;
import net.hydromatic.optiq.impl.StarTable;
import net.hydromatic.optiq.prepare.OptiqPrepareImpl;
import net.hydromatic.optiq.tools.Program;
import net.hydromatic.optiq.tools.Programs;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

/**
 * Records that a particular query is materialized by a particular table.
 */
public class RelOptMaterialization {
  public final RelNode tableRel;
  public final RelOptTable starRelOptTable;
  public final StarTable starTable;
  public final RelOptTable table;
  public final RelNode queryRel;

  /**
   * Creates a RelOptMaterialization.
   */
  public RelOptMaterialization(RelNode tableRel, RelNode queryRel,
      RelOptTable starRelOptTable) {
    this.tableRel = tableRel;
    this.starRelOptTable = starRelOptTable;
    if (starRelOptTable == null) {
      this.starTable = null;
    } else {
      this.starTable = starRelOptTable.unwrap(StarTable.class);
      assert starTable != null;
    }
    this.table = tableRel.getTable();
    this.queryRel = queryRel;
  }

  /**
   * Converts a relational expression to one that uses a
   * {@link net.hydromatic.optiq.impl.StarTable}.
   * The relational expression is already in leaf-join-form, per
   * {@link #toLeafJoinForm(org.eigenbase.rel.RelNode)}.
   */
  public static RelNode tryUseStar(RelNode rel,
      final RelOptTable starRelOptTable) {
    final StarTable starTable = starRelOptTable.unwrap(StarTable.class);
    assert starTable != null;
    RelNode rel2 = rel.accept(
        new RelShuttleImpl() {
          @Override
          public RelNode visit(TableAccessRelBase scan) {
            RelOptTable relOptTable = scan.getTable();
            final Table table = relOptTable.unwrap(Table.class);
            if (table.equals(starTable.tables.get(0))) {
              Mappings.TargetMapping mapping =
                  Mappings.createShiftMapping(
                      starRelOptTable.getRowType().getFieldCount(),
                      0, 0, relOptTable.getRowType().getFieldCount());

              final RelOptCluster cluster = scan.getCluster();
              final RelNode scan2 =
                  starRelOptTable.toRel(RelOptUtil.getContext(cluster));
              return RelOptUtil.createProject(scan2,
                  Mappings.asList(mapping.inverse()));
            }
            return scan;
          }

          @Override
          public RelNode visit(JoinRel join) {
            for (;;) {
              RelNode rel = super.visit(join);
              if (rel == join || !(rel instanceof JoinRel)) {
                return rel;
              }
              join = (JoinRel) rel;
              final ProjectFilterTable left =
                  ProjectFilterTable.of(join.getLeft());
              if (left != null) {
                final ProjectFilterTable right =
                    ProjectFilterTable.of(join.getRight());
                if (right != null) {
                  try {
                    match(left, right, join.getCluster());
                  } catch (Util.FoundOne e) {
                    return (RelNode) e.getNode();
                  }
                }
              }
            }
          }

          /** Throws a {@link org.eigenbase.util.Util.FoundOne} containing a
           * {@link org.eigenbase.rel.TableAccessRel} on success.
           * (Yes, an exception for normal operation.) */
          private void match(ProjectFilterTable left, ProjectFilterTable right,
              RelOptCluster cluster) {
            final Mappings.TargetMapping leftMapping = left.mapping();
            final Mappings.TargetMapping rightMapping = right.mapping();
            final RelOptTable leftRelOptTable = left.getTable();
            final Table leftTable = leftRelOptTable.unwrap(Table.class);
            final int leftCount = leftRelOptTable.getRowType().getFieldCount();
            final RelOptTable rightRelOptTable = right.getTable();
            final Table rightTable = rightRelOptTable.unwrap(Table.class);
            if (leftTable instanceof StarTable
                && ((StarTable) leftTable).tables.contains(rightTable)) {
              final int offset =
                  ((StarTable) leftTable).columnOffset(rightTable);
              Mappings.TargetMapping mapping =
                  Mappings.merge(leftMapping,
                      Mappings.offsetTarget(
                          Mappings.offsetSource(rightMapping, offset),
                          leftMapping.getTargetCount()));
              final RelNode project = RelOptUtil.createProject(
                  new TableAccessRel(cluster, leftRelOptTable),
                  Mappings.asList(mapping.inverse()));
              final List<RexNode> conditions = Lists.newArrayList();
              if (left.condition != null) {
                conditions.add(RexUtil.apply(mapping, left.condition));
              }
              if (right.condition != null) {
                conditions.add(
                    RexUtil.apply(mapping,
                        RexUtil.shift(right.condition, offset)));
              }
              final RelNode filter =
                  RelOptUtil.createFilter(project, conditions);
              throw new Util.FoundOne(filter);
            }
            if (rightTable instanceof StarTable
                && ((StarTable) rightTable).tables.contains(leftTable)) {
              final int offset =
                  ((StarTable) rightTable).columnOffset(leftTable);
              Mappings.TargetMapping mapping =
                  Mappings.merge(
                      Mappings.offsetSource(leftMapping, offset),
                      Mappings.offsetTarget(rightMapping, leftCount));
              final RelNode project = RelOptUtil.createProject(
                  new TableAccessRel(cluster, rightRelOptTable),
                  Mappings.asList(mapping.inverse()));
              final List<RexNode> conditions = Lists.newArrayList();
              if (left.condition != null) {
                conditions.add(RexUtil.apply(mapping, left.condition));
              }
              if (right.condition != null) {
                conditions.add(
                    RexUtil.apply(mapping,
                        RexUtil.shift(right.condition, offset)));
              }
              final RelNode filter =
                  RelOptUtil.createFilter(project, conditions);
              throw new Util.FoundOne(filter);
            }
          }
        });
    if (rel2 == rel) {
      return rel;
    }
    final Program program = Programs.hep(
        ImmutableList.of(PushProjectPastFilterRule.INSTANCE,
            AggregateProjectMergeRule.INSTANCE,
            AggregateFilterTransposeRule.INSTANCE),
        false,
        new DefaultRelMetadataProvider());
    return program.run(null, rel2, null);
  }

  /** A table scan and optional project mapping and filter condition. */
  private static class ProjectFilterTable {
    final RexNode condition;
    final Mappings.TargetMapping mapping;
    final TableAccessRelBase scan;

    private ProjectFilterTable(RexNode condition,
        Mappings.TargetMapping mapping, TableAccessRelBase scan) {
      this.condition = condition;
      this.mapping = mapping;
      this.scan = Preconditions.checkNotNull(scan);
    }

    static ProjectFilterTable of(RelNode node) {
      if (node instanceof FilterRelBase) {
        final FilterRelBase filter = (FilterRelBase) node;
        return of2(filter.getCondition(), filter.getChild());
      } else {
        return of2(null, node);
      }
    }

    private static ProjectFilterTable of2(RexNode condition, RelNode node) {
      if (node instanceof ProjectRelBase) {
        final ProjectRelBase project = (ProjectRelBase) node;
        return of3(condition, project.getMapping(), project.getChild());
      } else {
        return of3(condition, null, node);
      }
    }

    private static ProjectFilterTable of3(RexNode condition,
        Mappings.TargetMapping mapping, RelNode node) {
      if (node instanceof TableAccessRelBase) {
        return new ProjectFilterTable(condition, mapping,
            (TableAccessRelBase) node);
      } else {
        return null;
      }
    }

    public Mappings.TargetMapping mapping() {
      return mapping != null
          ? mapping
          : Mappings.createIdentity(scan.getRowType().getFieldCount());
    }

    public RelOptTable getTable() {
      return scan.getTable();
    }
  }

  /**
   * Converts a relational expression to a form where
   * {@link org.eigenbase.rel.JoinRel}s are
   * as close to leaves as possible.
   */
  public static RelNode toLeafJoinForm(RelNode rel) {
    final Program program = Programs.hep(
        ImmutableList.of(
            PullUpProjectsAboveJoinRule.RIGHT_PROJECT,
            PullUpProjectsAboveJoinRule.LEFT_PROJECT,
            PushFilterPastJoinRule.PushFilterIntoJoinRule.FILTER_ON_JOIN,
            MergeProjectRule.INSTANCE),
        false,
        new DefaultRelMetadataProvider());
    if (OptiqPrepareImpl.DEBUG) {
      System.out.println(
          RelOptUtil.dumpPlan(
              "before", rel, false, SqlExplainLevel.DIGEST_ATTRIBUTES));
    }
    final RelNode rel2 = program.run(null, rel, null);
    if (OptiqPrepareImpl.DEBUG) {
      System.out.println(
          RelOptUtil.dumpPlan(
              "after", rel2, false, SqlExplainLevel.DIGEST_ATTRIBUTES));
    }
    return rel2;
  }
}

// End RelOptMaterialization.java
