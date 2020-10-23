/*
 * Copyright (c) 2020, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.snow.consensus.snowball;

/**
 * @author hal.hildebrand
 *
 */

//BinarySlush is a slush instance deciding between two values. After performing
//a network sample of k nodes, if you have alpha votes for one of the choices,
//you should vote for that choice.
public interface BinarySlush {

    void initialize(int initialPreference);

    int preference();

    void recordSuccssfulPoll(int choice);

}
