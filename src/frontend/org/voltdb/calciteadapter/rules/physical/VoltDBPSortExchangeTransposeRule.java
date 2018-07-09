/* This file is part of VoltDB.
 * Copyright (C) 2008-2018 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
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
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.calciteadapter.rules.physical;

import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptRuleOperandChildPolicy;
import org.apache.calcite.plan.RelOptRuleOperandChildren;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelDistributions;
import org.apache.calcite.rel.RelNode;
import org.voltdb.calciteadapter.rel.physical.AbstractVoltDBPExchange;
import org.voltdb.calciteadapter.rel.physical.VoltDBPMergeExchange;
import org.voltdb.calciteadapter.rel.physical.VoltDBPSingeltonExchange;
import org.voltdb.calciteadapter.rel.physical.VoltDBPSort;

import com.google.common.collect.ImmutableList;

/**
 * Transform Sort / Exchange rels into
 *  a) Singleton Merge Exchange / Sort if the original Exchange relation is a Union or Merge Exchanges
 *  b) Singleton Exchange / Sort if the original Exchange relation is a Singleton
 */
public class VoltDBPSortExchangeTransposeRule extends RelOptRule {

    public static final VoltDBPSortExchangeTransposeRule INSTANCE= new VoltDBPSortExchangeTransposeRule();

    private VoltDBPSortExchangeTransposeRule() {
        super(
                operand(
                        VoltDBPSort.class,
                        RelDistributions.ANY,
                        new RelOptRuleOperandChildren(
                                RelOptRuleOperandChildPolicy.ANY,
                                ImmutableList.of(
                                        operand(AbstractVoltDBPExchange.class, any()))))
                );
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
        VoltDBPSort sortRel = call.rel(0);
        assert(sortRel != null);
        AbstractVoltDBPExchange exchangeRel = call.rel(1);

        VoltDBPSort newSortRel = sortRel.copy(
                // Update Limit distribution's trait
                sortRel.getTraitSet().plus(exchangeRel.getChildDistribution()),
                exchangeRel.getInput(),
                sortRel.getCollation(),
                sortRel.offset,
                sortRel.fetch);

        RelNode result = transposeExchange(exchangeRel, newSortRel);

        call.transformTo(result);
        // Remove the original rel from the search space
        call.getPlanner().setImportance(sortRel, 0);

    }

    private RelNode transposeExchange(AbstractVoltDBPExchange exchangeRel, VoltDBPSort sortRel) {
        // The new exchange that will be sitting above the Sort relation must have sort's collation trait
        // since the top relation is required to have collation matching the sort's one
        RelTraitSet newExchangeTraits = exchangeRel.getTraitSet().replace(sortRel.getCollation());
        if (exchangeRel instanceof VoltDBPSingeltonExchange) {
            AbstractVoltDBPExchange newExchange = exchangeRel.copy(
                    newExchangeTraits,
                    sortRel,
                    exchangeRel.getChildDistribution(),
                    exchangeRel.getLevel() + 1);
            return newExchange;
        } else {
            return new VoltDBPMergeExchange(
                    exchangeRel.getCluster(),
                    newExchangeTraits,
                    sortRel,
                    exchangeRel.getChildDistribution(),
                    exchangeRel.getChildSplitCount(),
                    exchangeRel.getLevel() + 1);
        }
    }

}