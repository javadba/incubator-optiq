/*
// Licensed to Julian Hyde under one or more contributor license
// agreements. See the NOTICE file distributed with this work for
// additional information regarding copyright ownership.
//
// Julian Hyde licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except in
// compliance with the License. You may obtain a copy of the License at:
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
*/
package org.eigenbase.relopt.volcano;

import org.eigenbase.rel.*;
import org.eigenbase.rel.metadata.*;

import com.google.common.base.Function;

/**
 * VolcanoRelMetadataProvider implements the {@link RelMetadataProvider}
 * interface by combining metadata from the rels making up an equivalence class.
 */
public class VolcanoRelMetadataProvider implements RelMetadataProvider {
  //~ Methods ----------------------------------------------------------------

  public Function<RelNode, Metadata> apply(Class<? extends RelNode> relClass,
      final Class<? extends Metadata> metadataClass) {
    if (relClass != RelSubset.class) {
      // let someone else further down the chain sort it out
      return null;
    }

    return new Function<RelNode, Metadata>() {
      public Metadata apply(RelNode rel) {
        RelSubset subset = (RelSubset) rel;

        // REVIEW jvs 29-Mar-2006: I'm not sure what the correct precedence
        // should be here.  Letting the current best plan take the first shot is
        // probably the right thing to do for physical estimates such as row
        // count.  Dunno about others, and whether we need a way to
        // discriminate.

        // First, try current best implementation.  If it knows how to answer
        // this query, treat it as the most reliable.
        if (subset.best != null) {
          final Function<RelNode, Metadata> function =
              rel.getCluster().getMetadataProvider().apply(
                  subset.best.getClass(), metadataClass);
          if (function != null) {
            Metadata metadata = function.apply(subset.best);
            if (metadata != null) {
              return metadata;
            }
          }
        }

        // Otherwise, try rels in same logical equivalence class to see if any
        // of them have a good answer.  We use the full logical equivalence
        // class rather than just the subset because many metadata providers
        // only know about logical metadata.

        // Equivalence classes can get tangled up in interesting ways, so avoid
        // an infinite loop.  REVIEW: There's a chance this will cause us to
        // fail on metadata queries which invoke other queries, e.g.
        // PercentageOriginalRows -> Selectivity.  If we implement caching at
        // this level, we could probably kill two birds with one stone (use
        // presence of pending cache entry to detect re-entrancy at the correct
        // granularity).
        if (subset.set.inMetadataQuery) {
          return null;
        }

        subset.set.inMetadataQuery = true;
        try {
          for (RelNode relCandidate : subset.set.rels) {
            final Function<RelNode, Metadata> function =
                rel.getCluster().getMetadataProvider().apply(
                    relCandidate.getClass(), metadataClass);
            if (function != null) {
              final Metadata result = function.apply(relCandidate);
              if (result != null) {
                return result;
              }
            }
          }
        } finally {
          subset.set.inMetadataQuery = false;
        }

        // Give up.
        return null;
      }
    };
  }
}

// End VolcanoRelMetadataProvider.java