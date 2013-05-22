/*
 * Copyright 2000-2013 Vaadin Ltd.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.vaadin.tests.components.ui;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;

import com.vaadin.server.VaadinRequest;
import com.vaadin.server.VaadinService;
import com.vaadin.tests.components.AbstractTestUIWithLog;
import com.vaadin.ui.Button;
import com.vaadin.ui.Button.ClickEvent;

public class UiAccess extends AbstractTestUIWithLog {

    private Future<Void> checkFromBeforeClientResponse;

    @Override
    protected void setup(VaadinRequest request) {
        addComponent(new Button("Access from UI thread",
                new Button.ClickListener() {

                    @Override
                    public void buttonClick(ClickEvent event) {
                        log.clear();
                        // Ensure beforeClientResponse is invoked
                        markAsDirty();
                        checkFromBeforeClientResponse = access(new Runnable() {
                            @Override
                            public void run() {
                                log("Access from UI thread is run");
                            }
                        });
                        log("Access from UI thread future is done? "
                                + checkFromBeforeClientResponse.isDone());
                    }
                }));
        addComponent(new Button("Access from background thread",
                new Button.ClickListener() {
                    @Override
                    public void buttonClick(ClickEvent event) {
                        log.clear();
                        final CountDownLatch latch = new CountDownLatch(1);

                        new Thread() {
                            @Override
                            public void run() {
                                final boolean threadHasCurrentResponse = VaadinService
                                        .getCurrentResponse() != null;
                                // session is locked by request thread at this
                                // point
                                final Future<Void> initialFuture = access(new Runnable() {
                                    @Override
                                    public void run() {
                                        log("Initial background message");
                                        log("Thread has current response? "
                                                + threadHasCurrentResponse);
                                    }
                                });

                                // Let request thread continue
                                latch.countDown();

                                // Wait until thread can be locked
                                while (!getSession().getLockInstance()
                                        .tryLock()) {
                                    try {
                                        Thread.sleep(100);
                                    } catch (InterruptedException e) {
                                        throw new RuntimeException(e);
                                    }
                                }
                                try {
                                    log("Thread got lock, inital future done? "
                                            + initialFuture.isDone());
                                    setPollInterval(-1);
                                } finally {
                                    getSession().unlock();
                                }
                                final Thread thisThread = this;
                                access(new Runnable() {
                                    @Override
                                    public void run() {
                                        log("Access has current response? "
                                                + (VaadinService
                                                        .getCurrentResponse() != null));
                                        log("Thread is still alive? "
                                                + thisThread.isAlive());
                                    }
                                });
                            }
                        }.start();

                        // Wait for thread to do initialize before continuing
                        try {
                            latch.await();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }

                        setPollInterval(3000);
                    }
                }));
        addComponent(new Button("Access throwing exception",
                new Button.ClickListener() {
                    @Override
                    public void buttonClick(ClickEvent event) {
                        log.clear();
                        final Future<Void> firstFuture = access(new Runnable() {
                            @Override
                            public void run() {
                                log("Throwing exception in access");
                                throw new RuntimeException(
                                        "Catch me if you can");
                            }
                        });
                        access(new Runnable() {
                            @Override
                            public void run() {
                                log("firstFuture is done? "
                                        + firstFuture.isDone());
                                try {
                                    firstFuture.get();
                                    log("Should not get here");
                                } catch (InterruptedException e) {
                                    throw new RuntimeException(e);
                                } catch (ExecutionException e) {
                                    log("Got exception from firstFuture: "
                                            + e.getMessage());
                                }
                            }
                        });
                    }
                }));
        addComponent(new Button("Cancel future before started",
                new Button.ClickListener() {
                    @Override
                    public void buttonClick(ClickEvent event) {
                        log.clear();
                        Future<Void> future = access(new Runnable() {
                            @Override
                            public void run() {
                                log("Should not get here");
                            }
                        });
                        future.cancel(false);
                        log("future was cancled, should not start");
                    }
                }));
        addComponent(new Button("Cancel running future",
                new Button.ClickListener() {
                    @Override
                    public void buttonClick(ClickEvent event) {
                        log.clear();
                        final ReentrantLock interruptLock = new ReentrantLock();

                        final Future<Void> future = access(new Runnable() {
                            @Override
                            public void run() {
                                log("Waiting for thread to start");
                                while (!interruptLock.isLocked()) {
                                    try {
                                        Thread.sleep(100);
                                    } catch (InterruptedException e) {
                                        log("Premature interruption");
                                        throw new RuntimeException(e);
                                    }
                                }

                                log("Thread started, waiting for interruption");
                                try {
                                    interruptLock.lockInterruptibly();
                                } catch (InterruptedException e) {
                                    log("I was interrupted");
                                }
                            }
                        });

                        new Thread() {
                            @Override
                            public void run() {
                                interruptLock.lock();
                                // Wait until UI thread has started waiting for
                                // the lock
                                while (!interruptLock.hasQueuedThreads()) {
                                    try {
                                        Thread.sleep(100);
                                    } catch (InterruptedException e) {
                                        throw new RuntimeException(e);
                                    }
                                }

                                future.cancel(true);
                            }
                        }.start();
                    }
                }));
    }

    @Override
    public void beforeClientResponse(boolean initial) {
        if (checkFromBeforeClientResponse != null) {
            log("beforeClientResponse future is done? "
                    + checkFromBeforeClientResponse.isDone());
            checkFromBeforeClientResponse = null;
        }
    }

    @Override
    protected String getTestDescription() {
        return "Test for various ways of using UI.access";
    }

    @Override
    protected Integer getTicketNumber() {
        return Integer.valueOf(11897);
    }

}
