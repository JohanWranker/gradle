/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.internal.artifacts.ivyservice.modulecache;

import com.google.common.base.Objects;
import com.google.common.collect.Maps;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.internal.artifacts.ImmutableModuleIdentifierFactory;
import org.gradle.api.internal.artifacts.ivyservice.ArtifactCacheMetadata;
import org.gradle.api.internal.artifacts.ivyservice.CacheLockingManager;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ModuleComponentRepository;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.result.AttributeContainerSerializer;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.result.ComponentIdentifierSerializer;
import org.gradle.api.internal.artifacts.repositories.metadata.IvyMutableModuleMetadataFactory;
import org.gradle.api.internal.artifacts.repositories.metadata.MavenMutableModuleMetadataFactory;
import org.gradle.cache.PersistentIndexedCache;
import org.gradle.internal.Factory;
import org.gradle.internal.component.external.model.ModuleComponentResolveMetadata;
import org.gradle.internal.component.external.model.MutableModuleComponentResolveMetadata;
import org.gradle.internal.resource.local.DefaultPathKeyFileStore;
import org.gradle.internal.serialize.AbstractSerializer;
import org.gradle.internal.serialize.Decoder;
import org.gradle.internal.serialize.Encoder;
import org.gradle.util.BuildCommencedTimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class DefaultModuleMetadataCache implements ModuleMetadataCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultModuleMetadataCache.class);

    final BuildCommencedTimeProvider timeProvider;
    private final CacheLockingManager cacheLockingManager;

    private final ModuleMetadataStore moduleMetadataStore;

    Map<ModuleComponentAtRepositoryKey, CachedMetadata> inMemoryCache =  Maps.newConcurrentMap();;
    private PersistentIndexedCache<ModuleComponentAtRepositoryKey, ModuleMetadataCacheEntry> cache;

    public DefaultModuleMetadataCache(BuildCommencedTimeProvider timeProvider,
                                      CacheLockingManager cacheLockingManager,
                                      ArtifactCacheMetadata artifactCacheMetadata,
                                      ImmutableModuleIdentifierFactory moduleIdentifierFactory,
                                      AttributeContainerSerializer attributeContainerSerializer,
                                      MavenMutableModuleMetadataFactory mavenMetadataFactory,
                                      IvyMutableModuleMetadataFactory ivyMetadataFactory) {
        this.timeProvider = timeProvider;
        this.cacheLockingManager = cacheLockingManager;
        moduleMetadataStore = new ModuleMetadataStore(new DefaultPathKeyFileStore(artifactCacheMetadata.getMetaDataStoreDirectory()), new ModuleMetadataSerializer(attributeContainerSerializer, mavenMetadataFactory, ivyMetadataFactory), moduleIdentifierFactory);
    }

    private PersistentIndexedCache<ModuleComponentAtRepositoryKey, ModuleMetadataCacheEntry> getCache() {
        if (cache == null) {
            cache = initCache();
        }
        return cache;
    }

    private PersistentIndexedCache<ModuleComponentAtRepositoryKey, ModuleMetadataCacheEntry> initCache() {
        return cacheLockingManager.createCache("module-metadata", new RevisionKeySerializer(), new ModuleMetadataCacheEntrySerializer());
    }

    public CachedMetadata getCachedModuleDescriptor(ModuleComponentRepository repository, ModuleComponentIdentifier componentId) {
        final ModuleComponentAtRepositoryKey key = createKey(repository, componentId);
        final CachedMetadata inMemory = inMemoryCache.get(key);
        if (inMemory != null) {
            return inMemory;
        }

        CachedMetadata cachedMetadata = loadCachedMetadata(key);
        if (cachedMetadata != null) {
            inMemoryCache.put(key, cachedMetadata);
            return cachedMetadata;
        }

        return null;
    }

    private CachedMetadata loadCachedMetadata(final ModuleComponentAtRepositoryKey key) {
        final PersistentIndexedCache<ModuleComponentAtRepositoryKey, ModuleMetadataCacheEntry> cache = getCache();
        return cacheLockingManager.useCache(new Factory<CachedMetadata>() {
            @Override
            public CachedMetadata create() {
                ModuleMetadataCacheEntry entry = cache.get(key);
                if (entry == null) {
                    return null;
                }
                if (entry.isMissing()) {
                    return new DefaultCachedMetadata(entry, null, timeProvider);
                }
                MutableModuleComponentResolveMetadata metadata = moduleMetadataStore.getModuleDescriptor(key);
                if (metadata == null) {
                    // Descriptor file has been deleted - ignore the entry
                    cache.remove(key);
                    return null;
                }
                return new DefaultCachedMetadata(entry, entry.configure(metadata), timeProvider);
            }
        });
    }

    public CachedMetadata cacheMissing(ModuleComponentRepository repository, ModuleComponentIdentifier id) {
        LOGGER.debug("Recording absence of module descriptor in cache: {} [changing = {}]", id, false);
        ModuleComponentAtRepositoryKey key = createKey(repository, id);
        ModuleMetadataCacheEntry entry = ModuleMetadataCacheEntry.forMissingModule(timeProvider.getCurrentTime());
        getCache().put(key, entry);
        DefaultCachedMetadata cachedMetaData = new DefaultCachedMetadata(entry, null, timeProvider);
        inMemoryCache.put(key, cachedMetaData);
        return cachedMetaData;
    }

    public CachedMetadata cacheMetaData(ModuleComponentRepository repository, final ModuleComponentIdentifier id, final ModuleComponentResolveMetadata metadata) {
        LOGGER.debug("Recording module descriptor in cache: {} [changing = {}]", metadata.getComponentId(), metadata.isChanging());
        final ModuleComponentAtRepositoryKey key = createKey(repository, id);
        return cacheLockingManager.useCache(new Factory<CachedMetadata>() {
            @Override
            public CachedMetadata create() {
                moduleMetadataStore.putModuleDescriptor(key, metadata);
                ModuleMetadataCacheEntry entry = createEntry(metadata);
                getCache().put(key, entry);
                DefaultCachedMetadata cachedMetaData = new DefaultCachedMetadata(entry, metadata, timeProvider);
                inMemoryCache.put(key, cachedMetaData);
                return cachedMetaData;
            }
        });
    }

    protected ModuleComponentAtRepositoryKey createKey(ModuleComponentRepository repository, ModuleComponentIdentifier id) {
        return new ModuleComponentAtRepositoryKey(repository.getId(), id);
    }

    protected ModuleMetadataCacheEntry createEntry(ModuleComponentResolveMetadata metaData) {
        return ModuleMetadataCacheEntry.forMetaData(metaData, timeProvider.getCurrentTime());
    }

    private static class RevisionKeySerializer extends AbstractSerializer<ModuleComponentAtRepositoryKey> {
        private final ComponentIdentifierSerializer componentIdSerializer = new ComponentIdentifierSerializer();

        public void write(Encoder encoder, ModuleComponentAtRepositoryKey value) throws Exception {
            encoder.writeString(value.getRepositoryId());
            componentIdSerializer.write(encoder, value.getComponentId());
        }

        public ModuleComponentAtRepositoryKey read(Decoder decoder) throws Exception {
            String resolverId = decoder.readString();
            ModuleComponentIdentifier identifier = (ModuleComponentIdentifier) componentIdSerializer.read(decoder);
            return new ModuleComponentAtRepositoryKey(resolverId, identifier);
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj)) {
                return false;
            }

            RevisionKeySerializer rhs = (RevisionKeySerializer) obj;
            return Objects.equal(componentIdSerializer, rhs.componentIdSerializer);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(super.hashCode(), componentIdSerializer);
        }
    }
}
