/*
 * Copyright 2010-2013 Ning, Inc.
 * Copyright 2014-2015 Groupon, Inc
 * Copyright 2014-2015 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.subscription.api.user;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.joda.time.DateTime;
import org.joda.time.Period;
import org.killbill.billing.account.api.Account;
import org.killbill.billing.account.api.AccountApiException;
import org.killbill.billing.account.api.AccountUserApi;
import org.killbill.billing.api.TestApiListener;
import org.killbill.billing.api.TestApiListener.NextEvent;
import org.killbill.billing.callcontext.InternalCallContext;
import org.killbill.billing.catalog.api.BillingPeriod;
import org.killbill.billing.catalog.api.Duration;
import org.killbill.billing.catalog.api.PhaseType;
import org.killbill.billing.catalog.api.PlanPhaseSpecifier;
import org.killbill.billing.catalog.api.PriceListSet;
import org.killbill.billing.catalog.api.ProductCategory;
import org.killbill.billing.catalog.api.TimeUnit;
import org.killbill.billing.events.EffectiveSubscriptionInternalEvent;
import org.killbill.billing.mock.MockAccountBuilder;
import org.killbill.billing.subscription.api.SubscriptionBaseInternalApi;
import org.killbill.billing.subscription.api.migration.SubscriptionBaseMigrationApi.AccountMigration;
import org.killbill.billing.subscription.api.migration.SubscriptionBaseMigrationApi.BundleMigration;
import org.killbill.billing.subscription.api.migration.SubscriptionBaseMigrationApi.SubscriptionMigration;
import org.killbill.billing.subscription.api.migration.SubscriptionBaseMigrationApi.SubscriptionMigrationCase;
import org.killbill.billing.subscription.engine.dao.SubscriptionDao;
import org.killbill.billing.subscription.events.SubscriptionBaseEvent;
import org.killbill.billing.subscription.events.phase.PhaseEvent;
import org.killbill.billing.subscription.events.user.ApiEvent;
import org.killbill.billing.subscription.events.user.ApiEventType;
import org.killbill.billing.util.callcontext.CallContext;
import org.killbill.clock.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class TestSubscriptionHelper {

    private final Logger log = LoggerFactory.getLogger(TestSubscriptionHelper.class);

    private final AccountUserApi accountApi;
    private final SubscriptionBaseInternalApi subscriptionApi;
    private final Clock clock;
    private final InternalCallContext internalCallContext;
    private final CallContext callContext;
    private final TestApiListener testListener;
    private final SubscriptionDao dao;

    @Inject
    public TestSubscriptionHelper(final AccountUserApi accountApi, final SubscriptionBaseInternalApi subscriptionApi, final Clock clock, final InternalCallContext internallCallContext, final CallContext callContext, final TestApiListener testListener, final SubscriptionDao dao) {
        this.accountApi = accountApi;
        this.subscriptionApi = subscriptionApi;
        this.clock = clock;
        this.internalCallContext = internallCallContext;
        this.callContext = callContext;
        this.testListener = testListener;
        this.dao = dao;
    }

    public DefaultSubscriptionBase createSubscription(final SubscriptionBaseBundle bundle, final String productName, final BillingPeriod term, final String planSet, final DateTime requestedDate)
            throws SubscriptionBaseApiException {
        return createSubscriptionWithBundle(bundle.getId(), productName, term, planSet, requestedDate);
    }

    public DefaultSubscriptionBase createSubscription(final SubscriptionBaseBundle bundle, final String productName, final BillingPeriod term, final String planSet)
            throws SubscriptionBaseApiException {
        return createSubscriptionWithBundle(bundle.getId(), productName, term, planSet, null);
    }

    public DefaultSubscriptionBase createSubscriptionWithBundle(final UUID bundleId, final String productName, final BillingPeriod term, final String planSet, final DateTime requestedDate)
            throws SubscriptionBaseApiException {
        testListener.pushExpectedEvent(NextEvent.CREATE);
        final DefaultSubscriptionBase subscription = (DefaultSubscriptionBase) subscriptionApi.createSubscription(bundleId,
                                                                                                                  new PlanPhaseSpecifier(productName, ProductCategory.BASE, term, planSet, null), null,
                                                                                                                  requestedDate == null ? clock.getUTCNow() : requestedDate, internalCallContext);
        assertNotNull(subscription);

        testListener.assertListenerStatus();

        return subscription;
    }

    public void checkNextPhaseChange(final DefaultSubscriptionBase subscription, final int expPendingEvents, final DateTime expPhaseChange) {
        final List<SubscriptionBaseEvent> events = dao.getPendingEventsForSubscription(subscription.getId(), internalCallContext);
        assertNotNull(events);
        printEvents(events);
        assertEquals(events.size(), expPendingEvents);
        if (events.size() > 0 && expPhaseChange != null) {
            boolean foundPhase = false;
            boolean foundChange = false;

            for (final SubscriptionBaseEvent cur : events) {
                if (cur instanceof PhaseEvent) {
                    assertEquals(foundPhase, false);
                    foundPhase = true;
                    assertEquals(cur.getEffectiveDate(), expPhaseChange);
                } else if (cur instanceof ApiEvent) {
                    final ApiEvent uEvent = (ApiEvent) cur;
                    assertEquals(ApiEventType.CHANGE, uEvent.getApiEventType());
                    assertEquals(foundChange, false);
                    foundChange = true;
                } else {
                    assertFalse(true);
                }
            }
        }
    }

    public void assertDateWithin(final DateTime in, final DateTime lower, final DateTime upper) {
        assertTrue(in.isEqual(lower) || in.isAfter(lower));
        assertTrue(in.isEqual(upper) || in.isBefore(upper));
    }

    public Duration getDurationMonth(final int months) {
        final Duration result = new Duration() {
            @Override
            public TimeUnit getUnit() {
                return TimeUnit.MONTHS;
            }

            @Override
            public int getNumber() {
                return months;
            }

            @Override
            public DateTime addToDateTime(final DateTime dateTime) {
                return null;  //To change body of implemented methods use File | Settings | File Templates.
            }

            @Override
            public Period toJodaPeriod() {
                throw new UnsupportedOperationException();
            }
        };
        return result;
    }

    public PlanPhaseSpecifier getProductSpecifier(final String productName, final String priceList,
                                                  final BillingPeriod term,
                                                  @Nullable final PhaseType phaseType) {
        return new PlanPhaseSpecifier(productName, ProductCategory.BASE, term, priceList, phaseType);
    }

    public void printEvents(final List<SubscriptionBaseEvent> events) {
        for (final SubscriptionBaseEvent cur : events) {
            log.debug("Inspect event " + cur);
        }
    }

    public void printSubscriptionTransitions(final List<EffectiveSubscriptionInternalEvent> transitions) {
        for (final EffectiveSubscriptionInternalEvent cur : transitions) {
            log.debug("Transition " + cur);
        }
    }

    /**
     * ***********************************************************
     * Utilities for migration tests
     * *************************************************************
     */

    public AccountMigration createAccountForMigrationTest(final List<List<SubscriptionMigrationCaseWithCTD>> cases) {
        final Account account;
        try {
            final Account accountData = new MockAccountBuilder().name(UUID.randomUUID().toString().substring(1, 8))
                                                                .email(UUID.randomUUID().toString().substring(1, 8))
                                                                .build();
            account = accountApi.createAccount(accountData, callContext);
        } catch (final AccountApiException e) {
            throw new AssertionError(e.getLocalizedMessage());
        }

        return new AccountMigration() {

            @Override
            public BundleMigration[] getBundles() {
                final List<BundleMigration> bundles = new ArrayList<BundleMigration>();
                final BundleMigration bundle0 = new BundleMigration() {
                    @Override
                    public SubscriptionMigration[] getSubscriptions() {
                        final SubscriptionMigration[] result = new SubscriptionMigration[cases.size()];

                        for (int i = 0; i < cases.size(); i++) {
                            final List<SubscriptionMigrationCaseWithCTD> curCases = cases.get(i);
                            final SubscriptionMigration subscription = new SubscriptionMigration() {
                                @Override
                                public SubscriptionMigrationCaseWithCTD[] getSubscriptionCases() {
                                    return curCases.toArray(new SubscriptionMigrationCaseWithCTD[curCases.size()]);
                                }

                                @Override
                                public ProductCategory getCategory() {
                                    return curCases.get(0).getPlanPhaseSpecifier().getProductCategory();
                                }

                                @Override
                                public DateTime getChargedThroughDate() {
                                    for (final SubscriptionMigrationCaseWithCTD cur : curCases) {
                                        if (cur.getChargedThroughDate() != null) {
                                            return cur.getChargedThroughDate();
                                        }
                                    }
                                    return null;
                                }
                            };
                            result[i] = subscription;
                        }
                        return result;
                    }

                    @Override
                    public String getBundleKey() {
                        return "12345";
                    }
                };
                bundles.add(bundle0);
                return bundles.toArray(new BundleMigration[bundles.size()]);
            }

            @Override
            public UUID getAccountKey() {
                return account.getId();
            }
        };
    }

    public AccountMigration createAccountForMigrationWithRegularBasePlanAndAddons(final DateTime initialBPstart, final DateTime initalAddonStart) {

        final List<SubscriptionMigrationCaseWithCTD> cases = new LinkedList<SubscriptionMigrationCaseWithCTD>();
        cases.add(new SubscriptionMigrationCaseWithCTD(
                new PlanPhaseSpecifier("Shotgun", ProductCategory.BASE, BillingPeriod.ANNUAL, PriceListSet.DEFAULT_PRICELIST_NAME, PhaseType.EVERGREEN),
                initialBPstart,
                null,
                initialBPstart.plusYears(1)));

        final List<SubscriptionMigrationCaseWithCTD> firstAddOnCases = new LinkedList<SubscriptionMigrationCaseWithCTD>();
        firstAddOnCases.add(new SubscriptionMigrationCaseWithCTD(
                new PlanPhaseSpecifier("Telescopic-Scope", ProductCategory.ADD_ON, BillingPeriod.MONTHLY, PriceListSet.DEFAULT_PRICELIST_NAME, PhaseType.DISCOUNT),
                initalAddonStart,
                initalAddonStart.plusMonths(1),
                initalAddonStart.plusMonths(1)));
        firstAddOnCases.add(new SubscriptionMigrationCaseWithCTD(
                new PlanPhaseSpecifier("Telescopic-Scope", ProductCategory.ADD_ON, BillingPeriod.MONTHLY, PriceListSet.DEFAULT_PRICELIST_NAME, PhaseType.EVERGREEN),
                initalAddonStart.plusMonths(1),
                null,
                null));

        final List<List<SubscriptionMigrationCaseWithCTD>> input = new ArrayList<List<SubscriptionMigrationCaseWithCTD>>();
        input.add(cases);
        input.add(firstAddOnCases);
        return createAccountForMigrationTest(input);
    }

    public AccountMigration createAccountForMigrationWithRegularBasePlan(final DateTime startDate) {
        final List<SubscriptionMigrationCaseWithCTD> cases = new LinkedList<SubscriptionMigrationCaseWithCTD>();
        cases.add(new SubscriptionMigrationCaseWithCTD(
                new PlanPhaseSpecifier("Shotgun", ProductCategory.BASE, BillingPeriod.ANNUAL, PriceListSet.DEFAULT_PRICELIST_NAME, PhaseType.EVERGREEN),
                startDate,
                null,
                startDate.plusYears(1)));
        final List<List<SubscriptionMigrationCaseWithCTD>> input = new ArrayList<List<SubscriptionMigrationCaseWithCTD>>();
        input.add(cases);
        return createAccountForMigrationTest(input);
    }

    public AccountMigration createAccountForMigrationWithRegularBasePlanFutreCancelled(final DateTime startDate) {
        final List<SubscriptionMigrationCaseWithCTD> cases = new LinkedList<SubscriptionMigrationCaseWithCTD>();
        cases.add(new SubscriptionMigrationCaseWithCTD(
                new PlanPhaseSpecifier("Assault-Rifle", ProductCategory.BASE, BillingPeriod.ANNUAL, PriceListSet.DEFAULT_PRICELIST_NAME, PhaseType.EVERGREEN),
                startDate,
                startDate.plusYears(1),
                startDate.plusYears(1)));
        final List<List<SubscriptionMigrationCaseWithCTD>> input = new ArrayList<List<SubscriptionMigrationCaseWithCTD>>();
        input.add(cases);
        return createAccountForMigrationTest(input);
    }

    public AccountMigration createAccountForMigrationFuturePendingPhase(final DateTime trialDate) {
        final List<SubscriptionMigrationCaseWithCTD> cases = new LinkedList<SubscriptionMigrationCaseWithCTD>();
        cases.add(new SubscriptionMigrationCaseWithCTD(
                new PlanPhaseSpecifier("Assault-Rifle", ProductCategory.BASE, BillingPeriod.MONTHLY, PriceListSet.DEFAULT_PRICELIST_NAME, PhaseType.TRIAL),
                trialDate,
                trialDate.plusDays(30),
                trialDate.plusDays(30)));
        cases.add(new SubscriptionMigrationCaseWithCTD(
                new PlanPhaseSpecifier("Assault-Rifle", ProductCategory.BASE, BillingPeriod.MONTHLY, PriceListSet.DEFAULT_PRICELIST_NAME, PhaseType.EVERGREEN),
                trialDate.plusDays(30),
                null,
                null));
        final List<List<SubscriptionMigrationCaseWithCTD>> input = new ArrayList<List<SubscriptionMigrationCaseWithCTD>>();
        input.add(cases);
        return createAccountForMigrationTest(input);
    }

    public AccountMigration createAccountForMigrationFuturePendingChange() {
        final List<SubscriptionMigrationCaseWithCTD> cases = new LinkedList<SubscriptionMigrationCaseWithCTD>();
        final DateTime effectiveDate = clock.getUTCNow().minusDays(10);
        cases.add(new SubscriptionMigrationCaseWithCTD(
                new PlanPhaseSpecifier("Assault-Rifle", ProductCategory.BASE, BillingPeriod.MONTHLY, PriceListSet.DEFAULT_PRICELIST_NAME, PhaseType.EVERGREEN),
                effectiveDate,
                effectiveDate.plusMonths(1),
                effectiveDate.plusMonths(1)));
        cases.add(new SubscriptionMigrationCaseWithCTD(
                new PlanPhaseSpecifier("Shotgun", ProductCategory.BASE, BillingPeriod.ANNUAL, PriceListSet.DEFAULT_PRICELIST_NAME, PhaseType.EVERGREEN),
                effectiveDate.plusMonths(1).plusDays(1),
                null,
                null));
        final List<List<SubscriptionMigrationCaseWithCTD>> input = new ArrayList<List<SubscriptionMigrationCaseWithCTD>>();
        input.add(cases);
        return createAccountForMigrationTest(input);
    }

    public static DateTime addOrRemoveDuration(final DateTime input, final List<Duration> durations, final boolean add) {
        DateTime result = input;
        for (final Duration cur : durations) {
            switch (cur.getUnit()) {
                case DAYS:
                    result = add ? result.plusDays(cur.getNumber()) : result.minusDays(cur.getNumber());
                    break;

                case MONTHS:
                    result = add ? result.plusMonths(cur.getNumber()) : result.minusMonths(cur.getNumber());
                    break;

                case YEARS:
                    result = add ? result.plusYears(cur.getNumber()) : result.minusYears(cur.getNumber());
                    break;
                case UNLIMITED:
                default:
                    throw new RuntimeException("Trying to move to unlimited time period");
            }
        }
        return result;
    }

    public static DateTime addDuration(final DateTime input, final List<Duration> durations) {
        return addOrRemoveDuration(input, durations, true);
    }

    public static DateTime addDuration(final DateTime input, final Duration duration) {
        final List<Duration> list = new ArrayList<Duration>();
        list.add(duration);
        return addOrRemoveDuration(input, list, true);
    }

    public static class SubscriptionMigrationCaseWithCTD implements SubscriptionMigrationCase {

        private final PlanPhaseSpecifier pps;
        private final DateTime effDt;
        private final DateTime cancelDt;
        private final DateTime ctd;

        public SubscriptionMigrationCaseWithCTD(final PlanPhaseSpecifier pps, final DateTime effDt, final DateTime cancelDt, final DateTime ctd) {
            this.pps = pps;
            this.cancelDt = cancelDt;
            this.effDt = effDt;
            this.ctd = ctd;
        }

        @Override
        public PlanPhaseSpecifier getPlanPhaseSpecifier() {
            return pps;
        }

        @Override
        public DateTime getEffectiveDate() {
            return effDt;
        }

        @Override
        public DateTime getCancelledDate() {
            return cancelDt;
        }

        public DateTime getChargedThroughDate() {
            return ctd;
        }
    }

}
