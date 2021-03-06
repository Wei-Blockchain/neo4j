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
package org.neo4j.kernel.impl.storemigration;

import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.neo4j.common.ProgressReporter;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.internal.helpers.collection.Iterables;
import org.neo4j.internal.id.DefaultIdGeneratorFactory;
import org.neo4j.internal.id.IdGenerator;
import org.neo4j.internal.id.IdGeneratorFactory;
import org.neo4j.internal.id.ScanOnOpenReadOnlyIdGeneratorFactory;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.layout.DatabaseFile;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.PageCursor;
import org.neo4j.io.pagecache.tracing.PageCacheTracer;
import org.neo4j.io.pagecache.tracing.cursor.PageCursorTracer;
import org.neo4j.kernel.impl.store.NeoStores;
import org.neo4j.kernel.impl.store.RecordStore;
import org.neo4j.kernel.impl.store.StoreFactory;
import org.neo4j.kernel.impl.store.StoreType;
import org.neo4j.kernel.impl.store.format.RecordFormats;
import org.neo4j.kernel.impl.store.record.AbstractBaseRecord;
import org.neo4j.kernel.impl.store.record.RecordLoad;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.storageengine.migration.AbstractStoreMigrationParticipant;

import static java.lang.Long.max;
import static org.eclipse.collections.api.factory.Sets.immutable;
import static org.neo4j.index.internal.gbptree.RecoveryCleanupWorkCollector.immediate;
import static org.neo4j.kernel.impl.store.format.RecordFormatSelector.selectForVersion;
import static org.neo4j.kernel.impl.store.format.RecordStorageCapability.GBPTREE_ID_FILES;
import static org.neo4j.kernel.impl.storemigration.FileOperation.MOVE;
import static org.neo4j.kernel.impl.storemigration.StoreMigratorFileOperation.fileOperation;

public class IdGeneratorMigrator extends AbstractStoreMigrationParticipant
{
    public static final String ID_GENERATOR_MIGRATION_TAG = "idGeneratorMigration";
    private final FileSystemAbstraction fileSystem;
    private final PageCache pageCache;
    private final Config config;
    private final PageCacheTracer cacheTracer;

    public IdGeneratorMigrator( FileSystemAbstraction fileSystem, PageCache pageCache, Config config, PageCacheTracer cacheTracer )
    {
        super( "Id files" );
        this.fileSystem = fileSystem;
        this.pageCache = pageCache;
        this.config = config;
        this.cacheTracer = cacheTracer;
    }

    @Override
    public void migrate( DatabaseLayout directoryLayout, DatabaseLayout migrationLayout, ProgressReporter progress, String versionToMigrateFrom,
            String versionToMigrateTo ) throws IOException
    {
        RecordFormats oldFormat = selectForVersion( versionToMigrateFrom );
        RecordFormats newFormat = selectForVersion( versionToMigrateTo );
        if ( requiresIdFilesMigration( oldFormat, newFormat ) )
        {
            try ( var cursorTracer = cacheTracer.createPageCursorTracer( ID_GENERATOR_MIGRATION_TAG ) )
            {
                migrateIdFiles( directoryLayout, migrationLayout, oldFormat, newFormat, cursorTracer );
            }
        }
    }

    private void migrateIdFiles( DatabaseLayout directoryLayout, DatabaseLayout migrationLayout, RecordFormats oldFormat, RecordFormats newFormat,
            PageCursorTracer cursorTracer ) throws IOException
    {
        // The store .id files needs to be migrated. At this point some of them have been sort-of-migrated, i.e. merely ported
        // to the new format, but just got the highId and nothing else. Regardless we want to do a proper migration here,
        // which not only means creating an empty id file w/ only highId. No, it also means scanning the stores and properly
        // updating its freelist so that from this point no ids will be lost, ever.
        // For every store type: if store is in migrationLayout use that, else use the one from the dbLayout because it will
        // be of the current format already. Then scan it and create the .id file in the migration layout.

        List<StoreType> storesInDbDirectory = new ArrayList<>();
        List<StoreType> storesInMigrationDirectory = new ArrayList<>();
        for ( StoreType storeType : StoreType.values() )
        {
            // See if it exists in migration directory, otherwise it must be in the db directory
            List<StoreType> list = fileSystem.fileExists( migrationLayout.file( storeType.getDatabaseFile() ) )
                                   ? storesInMigrationDirectory
                                   : storesInDbDirectory;
            list.add( storeType );
        }

        // The built id files will end up in this rebuiltIdGenerators
        IdGeneratorFactory rebuiltIdGenerators = new DefaultIdGeneratorFactory( fileSystem, immediate() );
        List<Pair<Path,Path>> renameList = new ArrayList<>();

        // Build the ones from the legacy (the current, really) directory that haven't been migrated
        try ( NeoStores stores = createStoreFactory( directoryLayout, oldFormat, new ScanOnOpenReadOnlyIdGeneratorFactory() ).openNeoStores(
                storesInDbDirectory.toArray( StoreType[]::new ) ) )
        {
            stores.start( cursorTracer );
            buildIdFiles( migrationLayout, storesInDbDirectory, rebuiltIdGenerators, renameList, stores, cursorTracer );
        }
        // Build the ones from the migration directory, those stores that have been migrated
        // Before doing this we will have to create empty stores for those that are missing, otherwise some of the stores
        // that we need to open will complain because some of their "sub-stores" doesn't exist. They will be empty, it's fine...
        // and we will not read from them at all. They will just sit there and allow their parent store to be opened.
        // We'll remove them after we have built the id files
        Set<Path> placeHolderStoreFiles = createEmptyPlaceHolderStoreFiles( migrationLayout, newFormat );
        try ( NeoStores stores = createStoreFactory( migrationLayout, newFormat, new ScanOnOpenReadOnlyIdGeneratorFactory() )
                .openNeoStores( storesInMigrationDirectory.toArray( StoreType[]::new ) ) )
        {
            stores.start( cursorTracer );
            buildIdFiles( migrationLayout, storesInMigrationDirectory, rebuiltIdGenerators, renameList, stores, cursorTracer );
        }
        for ( Path emptyPlaceHolderStoreFile : placeHolderStoreFiles )
        {
            fileSystem.deleteFile( emptyPlaceHolderStoreFile );
        }
        // Renamed the built id files (they're called what they should be called, except with a '.new' in the end) by removing the suffix
        for ( Pair<Path,Path> rename : renameList )
        {
            fileSystem.deleteFile( rename.getRight() );
            fileSystem.renameFile( rename.getLeft(), rename.getRight() );
        }
    }

    private Set<Path> createEmptyPlaceHolderStoreFiles( DatabaseLayout layout, RecordFormats format )
    {
        Set<Path> createdStores = new HashSet<>();
        StoreType[] storesToCreate = Stream.of( StoreType.values() ).filter( t ->
        {
            Path file = layout.file( t.getDatabaseFile() );
            boolean exists = fileSystem.fileExists( file );
            if ( !exists )
            {
                createdStores.add( file );
            }
            return !exists;
        } ).toArray( StoreType[]::new );
        createStoreFactory( layout, format, new ScanOnOpenReadOnlyIdGeneratorFactory() ).openNeoStores( true, storesToCreate ).close();
        return createdStores;
    }

    private void buildIdFiles( DatabaseLayout layout, List<StoreType> storeTypes, IdGeneratorFactory rebuiltIdGenerators,
        List<Pair<Path,Path>> renameMap, NeoStores stores, PageCursorTracer cursorTracer ) throws IOException
    {
        for ( StoreType storeType : storeTypes )
        {
            RecordStore<AbstractBaseRecord> store = stores.getRecordStore( storeType );
            long highId = store.getHighId();
            Path actualIdFile = layout.idFile( storeType.getDatabaseFile() ).get();
            Path idFile = actualIdFile.resolveSibling( actualIdFile.getFileName() + ".new" );
            renameMap.add( Pair.of( idFile, actualIdFile ) );
            boolean readOnly = config.get( GraphDatabaseSettings.read_only );
            int numberOfReservedLowIds = store.getNumberOfReservedLowIds();
            try ( PageCursor cursor = store.openPageCursorForReading( store.getNumberOfReservedLowIds(), cursorTracer );
                    // about maxId: let's not concern ourselves with maxId here; if it's in the store it can be in the id generator
                  IdGenerator idGenerator = rebuiltIdGenerators.create( pageCache, idFile, storeType.getIdType(), highId, true, Long.MAX_VALUE,
                            readOnly, cursorTracer, immutable.empty() ) )
            {
                idGenerator.start( visitor ->
                {
                    AbstractBaseRecord record = store.newRecord();
                    for ( long id = numberOfReservedLowIds; id < highId; id++ )
                    {
                        store.getRecordByCursor( id, record, RecordLoad.CHECK, cursor );
                        if ( !record.inUse() )
                        {
                            visitor.accept( id );
                            // it's enough with deleted - even if we don't mark the ids as free the next time we open the id generator they will be
                        }
                    }
                    return max( highId, numberOfReservedLowIds ) - 1;
                }, cursorTracer );
            }
        }
    }

    private boolean requiresIdFilesMigration( RecordFormats oldFormat, RecordFormats newFormat )
    {
        return !oldFormat.hasCapability( GBPTREE_ID_FILES ) && newFormat.hasCapability( GBPTREE_ID_FILES );
    }

    private StoreFactory createStoreFactory( DatabaseLayout databaseLayout, RecordFormats formats, IdGeneratorFactory idGeneratorFactory )
    {
        return new StoreFactory( databaseLayout, config, idGeneratorFactory, pageCache, fileSystem, formats, NullLogProvider.getInstance(), cacheTracer,
                immutable.empty() );
    }

    @Override
    public void moveMigratedFiles( DatabaseLayout migrationLayout, DatabaseLayout directoryLayout, String versionToMigrateFrom, String versionToMigrateTo )
            throws IOException
    {
        fileOperation( MOVE, fileSystem, migrationLayout, directoryLayout,
                Iterables.iterable( DatabaseFile.values() ), true, // allow to skip non existent source files
                ExistingTargetStrategy.OVERWRITE );
    }

    @Override
    public void cleanup( DatabaseLayout migrationLayout )
    {
        // There's nothing to clean up
    }
}
