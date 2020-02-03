/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.api.index;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.Callable;
import java.util.function.IntPredicate;

import org.neo4j.common.EntityType;
import org.neo4j.internal.helpers.collection.Visitor;
import org.neo4j.internal.kernel.api.InternalIndexState;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.internal.schema.IndexPrototype;
import org.neo4j.internal.schema.LabelSchemaDescriptor;
import org.neo4j.internal.schema.SchemaDescriptor;
import org.neo4j.internal.schema.SchemaState;
import org.neo4j.kernel.api.exceptions.index.FlipFailedKernelException;
import org.neo4j.kernel.api.exceptions.index.IndexEntryConflictException;
import org.neo4j.kernel.api.index.IndexPopulator;
import org.neo4j.kernel.api.index.IndexSample;
import org.neo4j.kernel.api.index.IndexUpdater;
import org.neo4j.kernel.impl.api.index.MultipleIndexPopulator.IndexPopulation;
import org.neo4j.kernel.impl.api.index.stats.IndexStatisticsStore;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.scheduler.JobScheduler;
import org.neo4j.storageengine.api.IndexEntryUpdate;
import org.neo4j.storageengine.api.NodePropertyAccessor;
import org.neo4j.test.InMemoryTokens;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.neo4j.kernel.api.index.IndexQueryHelper.add;

class MultipleIndexPopulatorTest
{
    private final LabelSchemaDescriptor index1 = SchemaDescriptor.forLabel( 1, 1 );
    private IndexStoreView indexStoreView;
    private SchemaState schemaState;
    private MultipleIndexPopulator multipleIndexPopulator;
    private IndexStatisticsStore indexStatisticsStore;
    private InMemoryTokens tokens;

    @BeforeEach
    void before()
    {
        indexStatisticsStore = mock( IndexStatisticsStore.class );
        indexStoreView = mock( IndexStoreView.class );
        when( indexStoreView.newPropertyAccessor() ).thenReturn( mock( NodePropertyAccessor.class ) );
        when( indexStoreView.visitNodes( any(), any(), any(), any(), anyBoolean() ) ).thenReturn( mock( StoreScan.class ) );
        schemaState = mock( SchemaState.class );
        JobScheduler jobScheduler = mock( JobScheduler.class );
        tokens = new InMemoryTokens();
        multipleIndexPopulator = new MultipleIndexPopulator(
                indexStoreView, NullLogProvider.getInstance(), EntityType.NODE, schemaState, indexStatisticsStore, jobScheduler, tokens );
    }

    @Test
    void disconnectedPopulationNotAbleToCreateNewIndex() throws FlipFailedKernelException
    {
        IndexPopulator populator = createIndexPopulator();
        IndexPopulation indexPopulation = addPopulator( populator, 1 );

        indexPopulation.disconnectAndStop();

        multipleIndexPopulator.create();

        verify( populator, never() ).create();
    }

    @Test
    void disconnectedPopulationNotAbleToFlip() throws FlipFailedKernelException
    {
        IndexPopulator populator = createIndexPopulator();
        IndexPopulation indexPopulation = addPopulator( populator, 1 );

        indexPopulation.disconnectAndStop();

        indexPopulation.flip( false );

        verify( indexPopulation.populator, never() ).sample();
    }

    @Test
    void flippedPopulationIsNotCloseable() throws FlipFailedKernelException
    {
        IndexPopulator populator = createIndexPopulator();
        IndexPopulation indexPopulation = addPopulator( populator, 1 );

        indexPopulation.flip( false );

        indexPopulation.disconnectAndStop();

        verify( indexPopulation.populator, never() ).close( false );
    }

    @Test
    void disconnectAndDropShouldCallDropOnPopulator() throws FlipFailedKernelException
    {
        IndexPopulator populator = createIndexPopulator();
        IndexPopulation indexPopulation = addPopulator( populator, 1 );

        indexPopulation.disconnectAndDrop();

        verify( populator, never() ).close( false );
        verify( populator ).drop();
    }

    @Test
    void testMultiplePopulatorsCreation() throws FlipFailedKernelException
    {
        IndexPopulator indexPopulator1 = createIndexPopulator();
        IndexPopulator indexPopulator2 = createIndexPopulator();
        addPopulator( indexPopulator1, 1 );
        addPopulator( indexPopulator2, 2 );

        multipleIndexPopulator.create();

        verify( indexPopulator1 ).create();
        verify( indexPopulator2 ).create();
    }

    @Test
    void testMultiplePopulatorCreationFailure() throws FlipFailedKernelException
    {
        IndexPopulator indexPopulator1 = createIndexPopulator();
        IndexPopulator indexPopulator2 = createIndexPopulator();
        IndexPopulator indexPopulator3 = createIndexPopulator();

        doThrow( getPopulatorException() ).when( indexPopulator1 ).create();
        doThrow( getPopulatorException() ).when( indexPopulator3 ).create();

        addPopulator( indexPopulator1, 1 );
        addPopulator( indexPopulator2, 2 );
        addPopulator( indexPopulator3, 3 );

        multipleIndexPopulator.create();

        checkPopulatorFailure( indexPopulator1 );
        checkPopulatorFailure( indexPopulator3 );

        verify( indexPopulator2 ).create();
    }

    @Test
    void testHasPopulators() throws FlipFailedKernelException
    {
        assertFalse( multipleIndexPopulator.hasPopulators() );

        addPopulator( createIndexPopulator(), 42 );

        assertTrue( multipleIndexPopulator.hasPopulators() );
    }

    @Test
    void stoppingSinglePopulatorDoNotStopAnyOther() throws FlipFailedKernelException
    {
        IndexPopulator indexPopulator1 = createIndexPopulator();
        IndexPopulator indexPopulator2 = createIndexPopulator();

        IndexPopulation populationToCancel = addPopulator( indexPopulator1, 1 );
        IndexPopulation populationToKeepActive = addPopulator( indexPopulator2, 2 );

        multipleIndexPopulator.create();

        multipleIndexPopulator.stop( populationToCancel );

        multipleIndexPopulator.indexAllEntities();

        assertTrue( multipleIndexPopulator.hasPopulators() );

        multipleIndexPopulator.flipAfterStoreScan( false );

        verify( populationToKeepActive.flipper ).flip( any( Callable.class ), any( FailedIndexProxyFactory.class ) );
    }

    @Test
    void stoppedPopulatorDoNotFlipWhenPopulationCompleted() throws FlipFailedKernelException
    {
        IndexPopulator indexPopulator1 = createIndexPopulator();
        IndexPopulator indexPopulator2 = createIndexPopulator();

        IndexPopulation populationToCancel = addPopulator( indexPopulator1, 1 );
        addPopulator( indexPopulator2, 2 );

        multipleIndexPopulator.create();

        multipleIndexPopulator.stop( populationToCancel );

        multipleIndexPopulator.indexAllEntities();

        assertTrue( multipleIndexPopulator.hasPopulators() );

        multipleIndexPopulator.flipAfterStoreScan( false );

        verify( populationToCancel.flipper, never() ).flip( any( Callable.class ), any( FailedIndexProxyFactory.class ) );
    }

    @Test
    void testIndexAllNodes() throws FlipFailedKernelException
    {
        IndexPopulator indexPopulator1 = createIndexPopulator();
        IndexPopulator indexPopulator2 = createIndexPopulator();

        addPopulator( indexPopulator1, 1 );
        addPopulator( indexPopulator2, 2 );

        multipleIndexPopulator.create();
        multipleIndexPopulator.indexAllEntities();

        verify( indexStoreView )
            .visitNodes( any( int[].class ), any( IntPredicate.class ), any( Visitor.class ), isNull(),
                anyBoolean() );
    }

    @Test
    void testCancelPopulator() throws FlipFailedKernelException
    {
        IndexPopulator indexPopulator1 = createIndexPopulator();
        IndexPopulator indexPopulator2 = createIndexPopulator();

        addPopulator( indexPopulator1, 1 );
        addPopulator( indexPopulator2, 2 );

        multipleIndexPopulator.cancel( getPopulatorException() );

        checkPopulatorFailure( indexPopulator1 );
        checkPopulatorFailure( indexPopulator2 );
    }

    @Test
    void testCancelByPopulation() throws FlipFailedKernelException
    {
        IndexPopulator populator1 = createIndexPopulator();
        IndexPopulator populator2 = createIndexPopulator();

        addPopulator( populator1, 1 );
        IndexPopulation population2 = addPopulator( populator2, 2 );

        multipleIndexPopulator.cancel( population2, getPopulatorException() );

        verify( populator1, never() ).markAsFailed( anyString() );
        checkPopulatorFailure( populator2 );
    }

    @Test
    void testCancelByPopulationRemovesPopulator() throws FlipFailedKernelException
    {
        IndexPopulator populator1 = createIndexPopulator();
        IndexPopulator populator2 = createIndexPopulator();

        IndexPopulation population1 = addPopulator( populator1, 1 );
        IndexPopulation population2 = addPopulator( populator2, 2 );

        multipleIndexPopulator.cancel( population1, getPopulatorException() );
        multipleIndexPopulator.cancel( population2, getPopulatorException() );

        checkPopulatorFailure( populator1 );
        checkPopulatorFailure( populator2 );
        assertFalse( multipleIndexPopulator.hasPopulators() );
    }

    @Test
    void testCancelByNonExistingPopulation() throws FlipFailedKernelException
    {
        IndexPopulation nonExistingPopulation = mock( IndexPopulation.class );
        IndexPopulator populator = createIndexPopulator();

        addPopulator( populator, 1 );

        multipleIndexPopulator.cancel( nonExistingPopulation, getPopulatorException() );

        verify( populator, never() ).markAsFailed( anyString() );
    }

    @Test
    void testFlipAfterStoreScan() throws FlipFailedKernelException
    {
        IndexPopulator indexPopulator1 = createIndexPopulator();
        IndexPopulator indexPopulator2 = createIndexPopulator();

        FlippableIndexProxy flipper1 = addPopulator( indexPopulator1, 1 ).flipper;
        FlippableIndexProxy flipper2 = addPopulator( indexPopulator2, 2 ).flipper;

        multipleIndexPopulator.flipAfterStoreScan( false );

        verify( flipper1 ).flip( any( Callable.class ), any( FailedIndexProxyFactory.class ) );
        verify( flipper2 ).flip( any( Callable.class ), any( FailedIndexProxyFactory.class ) );
    }

    @Test
    void populationsRemovedDuringFlip() throws FlipFailedKernelException
    {
        IndexPopulator indexPopulator1 = createIndexPopulator();
        IndexPopulator indexPopulator2 = createIndexPopulator();

        addPopulator( indexPopulator1, 1 );
        addPopulator( indexPopulator2, 2 );

        assertTrue( multipleIndexPopulator.hasPopulators() );

        multipleIndexPopulator.flipAfterStoreScan( false );

        assertFalse( multipleIndexPopulator.hasPopulators() );
    }

    @Test
    void testStopPopulation() throws FlipFailedKernelException
    {
        IndexPopulator indexPopulator1 = createIndexPopulator();
        IndexPopulator indexPopulator2 = createIndexPopulator();

        addPopulator( indexPopulator1, 1 );
        addPopulator( indexPopulator2, 2 );

        multipleIndexPopulator.stop();

        verify( indexStatisticsStore, times( 2 ) ).replaceStats( anyLong(), eq( new IndexSample( 0, 0, 0 ) ) );
        verify( indexPopulator1 ).close( false );
        verify( indexPopulator2 ).close( false );
    }

    @Test
    void testIndexFlip()
    {
        IndexProxyFactory indexProxyFactory = mock( IndexProxyFactory.class );
        FailedIndexProxyFactory failedIndexProxyFactory = mock( FailedIndexProxyFactory.class );
        FlippableIndexProxy flipper = new FlippableIndexProxy();
        flipper.setFlipTarget( indexProxyFactory );

        IndexPopulator indexPopulator1 = createIndexPopulator();
        IndexPopulator indexPopulator2 = createIndexPopulator();
        addPopulator( indexPopulator1, 1, flipper, failedIndexProxyFactory );
        addPopulator( indexPopulator2, 2, flipper, failedIndexProxyFactory );

        when( indexPopulator1.sample() ).thenThrow( getSampleError() );

        multipleIndexPopulator.indexAllEntities();
        multipleIndexPopulator.flipAfterStoreScan( false );

        verify( indexPopulator1 ).close( false );
        verify( failedIndexProxyFactory ).create( any( RuntimeException.class ) );

        verify( indexPopulator2 ).close( true );
        verify( indexPopulator2 ).sample();
        verify( indexStatisticsStore ).replaceStats( anyLong(), any() );
        verify( schemaState ).clear();
    }

    @Test
    void testMultiplePopulatorUpdater() throws IndexEntryConflictException, FlipFailedKernelException
    {
        IndexUpdater indexUpdater1 = mock( IndexUpdater.class );
        IndexPopulator indexPopulator1 = createIndexPopulator( indexUpdater1 );
        IndexPopulator indexPopulator2 = createIndexPopulator();

        addPopulator( indexPopulator1, 1 );
        addPopulator( indexPopulator2, 2 );

        doThrow( getPopulatorException() ).when( indexPopulator2 )
            .newPopulatingUpdater( any( NodePropertyAccessor.class ) );

        IndexUpdater multipleIndexUpdater =
            multipleIndexPopulator.newPopulatingUpdater( mock( NodePropertyAccessor.class ) );
        IndexEntryUpdate<?> propertyUpdate = createIndexEntryUpdate( index1 );
        multipleIndexUpdater.process( propertyUpdate );

        checkPopulatorFailure( indexPopulator2 );
        verify( indexUpdater1 ).process( propertyUpdate );
    }

    @Test
    void testNonApplicableUpdaterDoNotUpdatePopulator() throws IndexEntryConflictException, FlipFailedKernelException
    {
        IndexUpdater indexUpdater1 = mock( IndexUpdater.class );
        IndexPopulator indexPopulator1 = createIndexPopulator( indexUpdater1 );

        addPopulator( indexPopulator1, 2 );

        IndexUpdater multipleIndexUpdater =
            multipleIndexPopulator.newPopulatingUpdater( mock( NodePropertyAccessor.class ) );

        IndexEntryUpdate<?> propertyUpdate = createIndexEntryUpdate( index1 );
        multipleIndexUpdater.process( propertyUpdate );

        verifyZeroInteractions( indexUpdater1 );
    }

    @Test
    void testPropertyUpdateFailure() throws IndexEntryConflictException, FlipFailedKernelException
    {
        IndexEntryUpdate<?> propertyUpdate = createIndexEntryUpdate( index1 );
        IndexUpdater indexUpdater1 = mock( IndexUpdater.class );
        IndexPopulator indexPopulator1 = createIndexPopulator( indexUpdater1 );

        addPopulator( indexPopulator1, 1 );

        doThrow( getPopulatorException() ).when( indexUpdater1 ).process( propertyUpdate );

        IndexUpdater multipleIndexUpdater =
            multipleIndexPopulator.newPopulatingUpdater( mock( NodePropertyAccessor.class ) );

        multipleIndexUpdater.process( propertyUpdate );

        verify( indexUpdater1 ).close();
        checkPopulatorFailure( indexPopulator1 );
    }

    @Test
    void testMultiplePropertyUpdateFailures() throws IndexEntryConflictException, FlipFailedKernelException
    {
        NodePropertyAccessor nodePropertyAccessor = mock( NodePropertyAccessor.class );
        IndexEntryUpdate<?> update1 = add( 1, index1, "foo" );
        IndexEntryUpdate<?> update2 = add( 2, index1, "bar" );
        IndexUpdater updater = mock( IndexUpdater.class );
        IndexPopulator populator = createIndexPopulator( updater );

        addPopulator( populator, 1 );

        doThrow( getPopulatorException() ).when( updater ).process( any( IndexEntryUpdate.class ) );

        IndexUpdater multipleIndexUpdater = multipleIndexPopulator.newPopulatingUpdater( nodePropertyAccessor );

        multipleIndexUpdater.process( update1 );
        multipleIndexUpdater.process( update2 );

        verify( updater ).process( update1 );
        verify( updater, never() ).process( update2 );
        verify( updater ).close();
        checkPopulatorFailure( populator );
    }

    @Test
    void shouldVerifyConstraintsBeforeFlippingIfToldTo() throws IndexEntryConflictException
    {
        // given
        IndexProxyFactory indexProxyFactory = mock( IndexProxyFactory.class );
        FailedIndexProxyFactory failedIndexProxyFactory = mock( FailedIndexProxyFactory.class );
        FlippableIndexProxy flipper = new FlippableIndexProxy();
        flipper.setFlipTarget( indexProxyFactory );
        IndexPopulator indexPopulator = createIndexPopulator();
        addPopulator( indexPopulator, 1, flipper, failedIndexProxyFactory );
        when( indexPopulator.sample() ).thenReturn( new IndexSample() );

        // when
        multipleIndexPopulator.indexAllEntities();
        multipleIndexPopulator.flipAfterStoreScan( true );

        // then
        verify( indexPopulator ).verifyDeferredConstraints( any( NodePropertyAccessor.class ) );
        verify( indexPopulator ).close( true );
    }

    @Test
    void shouldIncludeIndexSampleUpdatesInStatsOnFlip()
    {
        IndexProxyFactory indexProxyFactory = mock( IndexProxyFactory.class );
        FailedIndexProxyFactory failedIndexProxyFactory = mock( FailedIndexProxyFactory.class );
        FlippableIndexProxy flipper = new FlippableIndexProxy();
        flipper.setFlipTarget( indexProxyFactory );

        IndexPopulator indexPopulator = createIndexPopulator();
        addPopulator( indexPopulator, 1, flipper, failedIndexProxyFactory );

        int indexSize = 100;
        int uniqueValues = 110;
        int sampleSize = 120;
        int updates = 130;
        IndexSample sample = new IndexSample( indexSize, uniqueValues, sampleSize, updates );
        when( indexPopulator.sample() ).thenReturn( sample );

        multipleIndexPopulator.indexAllEntities();
        multipleIndexPopulator.flipAfterStoreScan( false );

        verify( indexPopulator ).close( true );

        verify( indexStatisticsStore ).replaceStats( 1, sample );
        verify( schemaState ).clear();
    }

    private static IndexEntryUpdate<?> createIndexEntryUpdate( LabelSchemaDescriptor schemaDescriptor )
    {
        return add( 1, schemaDescriptor, "theValue" );
    }

    private static RuntimeException getSampleError()
    {
        return new RuntimeException( "sample error" );
    }

    private static IndexPopulator createIndexPopulator( IndexUpdater indexUpdater )
    {
        IndexPopulator indexPopulator = createIndexPopulator();
        when( indexPopulator.newPopulatingUpdater( any( NodePropertyAccessor.class ) ) ).thenReturn( indexUpdater );
        return indexPopulator;
    }

    private static IndexPopulator createIndexPopulator()
    {
        IndexPopulator populator = mock( IndexPopulator.class );
        when( populator.sample() ).thenReturn( new IndexSample() );
        return populator;
    }

    private static UncheckedIOException getPopulatorException()
    {
        return new UncheckedIOException( new IOException( "something went wrong" ) );
    }

    private static void checkPopulatorFailure( IndexPopulator populator )
    {
        verify( populator ).markAsFailed( contains( "something went wrong" ) );
        verify( populator ).close( false );
    }

    private IndexPopulation addPopulator( IndexPopulator indexPopulator, int id,
        FlippableIndexProxy flippableIndexProxy, FailedIndexProxyFactory failedIndexProxyFactory )
    {
        return addPopulator( multipleIndexPopulator, indexPopulator, id, flippableIndexProxy, failedIndexProxyFactory );
    }

    private IndexPopulation addPopulator( MultipleIndexPopulator multipleIndexPopulator, IndexPopulator indexPopulator,
        int id, FlippableIndexProxy flippableIndexProxy, FailedIndexProxyFactory failedIndexProxyFactory )
    {
        IndexDescriptor descriptor = IndexPrototype.forSchema( SchemaDescriptor.forLabel( id, id ) ).withName( "index_" + id ).materialise( id );
        return addPopulator( multipleIndexPopulator, descriptor, indexPopulator, flippableIndexProxy, failedIndexProxyFactory );
    }

    private IndexPopulation addPopulator( MultipleIndexPopulator multipleIndexPopulator, IndexDescriptor descriptor, IndexPopulator indexPopulator,
        FlippableIndexProxy flippableIndexProxy, FailedIndexProxyFactory failedIndexProxyFactory )
    {
        return multipleIndexPopulator.addPopulator( indexPopulator, descriptor, flippableIndexProxy, failedIndexProxyFactory, "userIndexDescription" );
    }

    private IndexPopulation addPopulator( IndexPopulator indexPopulator, int id ) throws FlipFailedKernelException
    {
        FlippableIndexProxy indexProxy = mock( FlippableIndexProxy.class );
        when( indexProxy.getState() ).thenReturn( InternalIndexState.ONLINE );
        doAnswer( invocation ->
        {
            Callable argument = invocation.getArgument( 0 );
            return argument.call();
        } ).when( indexProxy ).flip( any( Callable.class ), any( FailedIndexProxyFactory.class ) );
        return addPopulator( indexPopulator, id, indexProxy, mock( FailedIndexProxyFactory.class ) );
    }
}
