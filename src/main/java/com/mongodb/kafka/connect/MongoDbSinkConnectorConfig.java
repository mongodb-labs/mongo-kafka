/*
 * Copyright 2008-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Original Work: Apache License, Version 2.0, Copyright 2017 Hans-Peter Grahsl.
 */

package com.mongodb.kafka.connect;

import static java.lang.String.format;
import static java.util.Collections.emptyList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigDef.Importance;
import org.apache.kafka.common.config.ConfigDef.Type;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.config.ConfigValue;

import org.bson.Document;
import org.bson.json.JsonParseException;

import com.mongodb.ConnectionString;

import com.mongodb.kafka.connect.cdc.CdcHandler;
import com.mongodb.kafka.connect.cdc.debezium.mongodb.MongoDbHandler;
import com.mongodb.kafka.connect.cdc.debezium.rdbms.RdbmsHandler;
import com.mongodb.kafka.connect.cdc.debezium.rdbms.mysql.MysqlHandler;
import com.mongodb.kafka.connect.cdc.debezium.rdbms.postgres.PostgresHandler;
import com.mongodb.kafka.connect.processor.BlacklistKeyProjector;
import com.mongodb.kafka.connect.processor.BlacklistValueProjector;
import com.mongodb.kafka.connect.processor.DocumentIdAdder;
import com.mongodb.kafka.connect.processor.PostProcessor;
import com.mongodb.kafka.connect.processor.WhitelistKeyProjector;
import com.mongodb.kafka.connect.processor.WhitelistValueProjector;
import com.mongodb.kafka.connect.processor.field.projection.FieldProjector;
import com.mongodb.kafka.connect.processor.field.renaming.RegExpSettings;
import com.mongodb.kafka.connect.processor.id.strategy.BsonOidStrategy;
import com.mongodb.kafka.connect.processor.id.strategy.FullKeyStrategy;
import com.mongodb.kafka.connect.processor.id.strategy.IdStrategy;
import com.mongodb.kafka.connect.processor.id.strategy.KafkaMetaDataStrategy;
import com.mongodb.kafka.connect.processor.id.strategy.PartialKeyStrategy;
import com.mongodb.kafka.connect.processor.id.strategy.PartialValueStrategy;
import com.mongodb.kafka.connect.processor.id.strategy.ProvidedInKeyStrategy;
import com.mongodb.kafka.connect.processor.id.strategy.ProvidedInValueStrategy;
import com.mongodb.kafka.connect.processor.id.strategy.UuidStrategy;
import com.mongodb.kafka.connect.writemodel.strategy.DeleteOneDefaultStrategy;
import com.mongodb.kafka.connect.writemodel.strategy.WriteModelStrategy;

public class MongoDbSinkConnectorConfig extends CollectionAwareConfig {

    public enum FieldProjectionTypes {
        NONE,
        BLACKLIST,
        WHITELIST
    }

    private static final Pattern CLASS_NAME = Pattern.compile("\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*");
    private static final Pattern FULLY_QUALIFIED_CLASS_NAME = Pattern.compile("(" + CLASS_NAME + "\\.)*" + CLASS_NAME);
    private static final Pattern FULLY_QUALIFIED_CLASS_NAME_LIST = Pattern.compile("(" + FULLY_QUALIFIED_CLASS_NAME + ",)*" + FULLY_QUALIFIED_CLASS_NAME);

    public static final String FIELD_LIST_SPLIT_CHAR = ",";
    public static final String FIELD_LIST_SPLIT_EXPR = "\\s*" + FIELD_LIST_SPLIT_CHAR + "\\s*";

    public static final String TOPIC_AGNOSTIC_KEY_NAME = "__default__";
    public static final String MONGODB_NAMESPACE_SEPARATOR = ".";

    public static final String MONGODB_ID_FIELD = "_id";
    public static final String MONGODB_CONNECTION_URI_DEFAULT = "mongodb://localhost:27017/kafkaconnect?w=1&journal=true";
    public static final String MONGODB_COLLECTIONS_DEFAULT = "";
    public static final String MONGODB_COLLECTION_DEFAULT = "";
    public static final int MONGODB_MAX_NUM_RETRIES_DEFAULT = 3;
    public static final int MONGODB_RETRIES_DEFER_TIMEOUT_DEFAULT = 5000;
    public static final String MONGODB_VALUE_PROJECTION_TYPE_DEFAULT = "none";
    public static final String MONGODB_VALUE_PROJECTION_LIST_DEFAULT = "";
    public static final String MONGODB_DOCUMENT_ID_STRATEGY_DEFAULT = "com.mongodb.kafka.connect.processor.id.strategy.BsonOidStrategy";
    public static final String MONGODB_DOCUMENT_ID_STRATEGIES_DEFAULT = "";
    public static final String MONGODB_KEY_PROJECTION_TYPE_DEFAULT = "none";
    public static final String MONGODB_KEY_PROJECTION_LIST_DEFAULT = "";
    public static final String MONGODB_FIELD_RENAMER_MAPPING_DEFAULT = "[]";
    public static final String MONGODB_FIELD_RENAMER_REGEXP_DEFAULT = "[]";
    public static final String MONGODB_POST_PROCESSOR_CHAIN_DEFAULT = "com.mongodb.kafka.connect.processor.DocumentIdAdder";
    public static final String MONGODB_CHANGE_DATA_CAPTURE_HANDLER_DEFAULT = "";
    public static final boolean MONGODB_DELETE_ON_NULL_VALUES_DEFAULT = false;
    public static final String MONGODB_WRITEMODEL_STRATEGY_DEFAULT = "com.mongodb.kafka.connect.writemodel.strategy.ReplaceOneDefaultStrategy";
    public static final int MONGODB_MAX_BATCH_SIZE_DEFAULT = 0;
    public static final int MONGODB_RATE_LIMITING_TIMEOUT_DEFAULT = 0;
    public static final int MONGODB_RATE_LIMITING_EVERY_N_DEFAULT = 0;

    public static final String MONGODB_CONNECTION_URI_CONF = "mongodb.connection.uri";
    private static final String MONGODB_CONNECTION_URI_DOC = "the mongodb connection URI as supported by the official drivers";

    public static final String MONGODB_COLLECTION_CONF = "mongodb.collection";
    private static final String MONGODB_COLLECTION_DOC = "single sink collection name to write to";

    public static final String MONGODB_COLLECTIONS_CONF = "mongodb.collections";
    private static final String MONGODB_COLLECTIONS_DOC = "names of sink collections to write to for which there can be topic-level specific properties defined";

    public static final String MONGODB_MAX_NUM_RETRIES_CONF = "mongodb.max.num.retries";
    private static final String MONGODB_MAX_NUM_RETRIES_DOC = "how often a retry should be done on write errors";

    public static final String MONGODB_RETRIES_DEFER_TIMEOUT_CONF = "mongodb.retries.defer.timeout";
    private static final String MONGODB_RETRIES_DEFER_TIMEOUT_DOC = "how long in ms a retry should get deferred";

    public static final String MONGODB_VALUE_PROJECTION_TYPE_CONF = "mongodb.value.projection.type";
    private static final String MONGODB_VALUE_PROJECTION_TYPE_DOC = "whether or not and which value projection to use";

    public static final String MONGODB_VALUE_PROJECTION_LIST_CONF = "mongodb.value.projection.list";
    private static final String MONGODB_VALUE_PROJECTION_LIST_DOC = "comma separated list of field names for value projection";

    public static final String MONGODB_DOCUMENT_ID_STRATEGY_CONF = "mongodb.document.id.strategy";
    private static final String MONGODB_DOCUMENT_ID_STRATEGY_CONF_DOC = "class name of strategy to use for generating a unique document id (_id)";

    public static final String MONGODB_DOCUMENT_ID_STRATEGIES_CONF = "mongodb.document.id.strategies";
    private static final String MONGODB_DOCUMENT_ID_STRATEGIES_CONF_DOC = "comma separated list of custom strategy classes to register for usage";

    public static final String MONGODB_KEY_PROJECTION_TYPE_CONF = "mongodb.key.projection.type";
    private static final String MONGODB_KEY_PROJECTION_TYPE_DOC = "whether or not and which key projection to use";

    public static final String MONGODB_KEY_PROJECTION_LIST_CONF = "mongodb.key.projection.list";
    private static final String MONGODB_KEY_PROJECTION_LIST_DOC = "comma separated list of field names for key projection";

    public static final String MONGODB_FIELD_RENAMER_MAPPING = "mongodb.field.renamer.mapping";
    private static final String MONGODB_FIELD_RENAMER_MAPPING_DOC = "inline JSON array with objects describing field name mappings";

    public static final String MONGODB_FIELD_RENAMER_REGEXP = "mongodb.field.renamer.regexp";
    private static final String MONGODB_FIELD_RENAMER_REGEXP_DOC = "inline JSON array with objects describing regexp settings";

    public static final String MONGODB_POST_PROCESSOR_CHAIN = "mongodb.post.processor.chain";
    private static final String MONGODB_POST_PROCESSOR_CHAIN_DOC = "comma separated list of post processor classes to build the chain with";

    public static final String MONGODB_CHANGE_DATA_CAPTURE_HANDLER = "mongodb.change.data.capture.handler";
    private static final String MONGODB_CHANGE_DATA_CAPTURE_HANDLER_DOC = "class name of CDC handler to use for processing";

    public static final String MONGODB_DELETE_ON_NULL_VALUES = "mongodb.delete.on.null.values";
    private static final String MONGODB_DELETE_ON_NULL_VALUES_DOC = "whether or not the connector tries to delete documents based on key when value is null";

    public static final String MONGODB_WRITEMODEL_STRATEGY = "mongodb.writemodel.strategy";
    private static final String MONGODB_WRITEMODEL_STRATEGY_DOC = "how to build the write models for the sink documents";

    public static final String MONGODB_MAX_BATCH_SIZE = "mongodb.max.batch.size";
    private static final String MONGODB_MAX_BATCH_SIZE_DOC = "maximum number of sink records to possibly batch together for processing";

    public static final String MONGODB_RATE_LIMITING_TIMEOUT = "mongodb.rate.limiting.timeout";
    private static final String MONGODB_RATE_LIMITING_TIMEOUT_DOC = "how long in ms processing should wait before continue processing";

    public static final String MONGODB_RATE_LIMITING_EVERY_N = "mongodb.rate.limiting.every.n";
    private static final String MONGODB_RATE_LIMITING_EVERY_N_DOC = "after how many processed batches the rate limit should trigger (NO rate limiting if n=0)";

    public static class RateLimitSettings {

        private final int timeoutMs;
        private final int everyN;
        private long counter;

        public RateLimitSettings(final int timeoutMs, final int everyN) {
            this.timeoutMs = timeoutMs;
            this.everyN = everyN;
        }

        public boolean isTriggered() {
            counter++;
            return (everyN != 0)
                    && (counter >= everyN)
                    && (counter % everyN == 0);
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public int getEveryN() {
            return everyN;
        }

        public long getCounter() {
            return counter;
        }
    }

    public MongoDbSinkConnectorConfig(final ConfigDef config, final Map<String, String> parsedConfig) {
        super(config, parsedConfig);
    }

    public MongoDbSinkConnectorConfig(final Map<String, String> parsedConfig) {
        this(conf(), parsedConfig);
    }

    public static ConfigDef conf() {
        return new ConfigDef() {

            private <T> Validator<T> ensureValid(final String name, final Consumer<T> consumer) {
                return new Validator<>(name, consumer);
            }

            class Validator<T> {

                private final String name;
                private final Consumer<T> consumer;

                Validator(final String name, final Consumer<T> consumer) {
                    this.name = name;
                    this.consumer = consumer;
                }

                private Validator<T> unless(final boolean condition) {
                    return condition ? new Validator<T>(name, (T t) -> {
                    }) : this;
                }

                private void accept(final T obj) {
                    this.consumer.accept(obj);
                }
            }

            @Override
            public Map<String, ConfigValue> validateAll(final Map<String, String> props) {
                Map<String, ConfigValue> result = super.validateAll(props);
                MongoDbSinkConnectorConfig config = new MongoDbSinkConnectorConfig(props);
                Stream.of(
                        ensureValid(MONGODB_CONNECTION_URI_CONF, MongoDbSinkConnectorConfig::getConnectionString),
                        ensureValid(MONGODB_KEY_PROJECTION_TYPE_CONF,
                                (MongoDbSinkConnectorConfig cfg) -> cfg.getKeyProjectionList("")),
                        ensureValid(MONGODB_VALUE_PROJECTION_TYPE_CONF,
                                (MongoDbSinkConnectorConfig cfg) -> cfg.getValueProjectionList("")),
                        ensureValid(MONGODB_FIELD_RENAMER_MAPPING,
                                (MongoDbSinkConnectorConfig cfg) -> cfg.parseRenameFieldnameMappings("")),
                        ensureValid(MONGODB_FIELD_RENAMER_REGEXP,
                                (MongoDbSinkConnectorConfig cfg) -> cfg.parseRenameRegExpSettings("")),
                        ensureValid(MONGODB_POST_PROCESSOR_CHAIN,
                                (MongoDbSinkConnectorConfig cfg) -> cfg.buildPostProcessorChain("")),
                        ensureValid(MONGODB_CHANGE_DATA_CAPTURE_HANDLER,
                                (MongoDbSinkConnectorConfig cfg) -> cfg.getCdcHandler(""))
                                .unless(config.getString(MONGODB_CHANGE_DATA_CAPTURE_HANDLER).isEmpty()),
                        ensureValid(MONGODB_DOCUMENT_ID_STRATEGIES_CONF,
                                (MongoDbSinkConnectorConfig cfg) -> cfg.getIdStrategy(""))
                ).forEach(validator -> {
                    try {
                        validator.accept(config);
                    } catch (Exception ex) {
                        result.get(validator.name).addErrorMessage(ex.getMessage());
                    }
                });
                return result;
            }
        }
                .define(MONGODB_CONNECTION_URI_CONF, Type.STRING, MONGODB_CONNECTION_URI_DEFAULT, Importance.HIGH, MONGODB_CONNECTION_URI_DOC)
                .define(MONGODB_COLLECTIONS_CONF, Type.STRING, MONGODB_COLLECTIONS_DEFAULT, Importance.MEDIUM, MONGODB_COLLECTIONS_DOC)
                .define(MONGODB_COLLECTION_CONF, Type.STRING, MONGODB_COLLECTION_DEFAULT, Importance.HIGH, MONGODB_COLLECTION_DOC)
                .define(MONGODB_MAX_NUM_RETRIES_CONF, Type.INT, MONGODB_MAX_NUM_RETRIES_DEFAULT, ConfigDef.Range.atLeast(0), Importance.MEDIUM, MONGODB_MAX_NUM_RETRIES_DOC)
                .define(MONGODB_RETRIES_DEFER_TIMEOUT_CONF, Type.INT, MONGODB_RETRIES_DEFER_TIMEOUT_DEFAULT, ConfigDef.Range.atLeast(0), Importance.MEDIUM, MONGODB_RETRIES_DEFER_TIMEOUT_DOC)
                .define(MONGODB_VALUE_PROJECTION_TYPE_CONF, Type.STRING, MONGODB_VALUE_PROJECTION_TYPE_DEFAULT, EnumValidator.in(FieldProjectionTypes.values()), Importance.LOW, MONGODB_VALUE_PROJECTION_TYPE_DOC)
                .define(MONGODB_VALUE_PROJECTION_LIST_CONF, Type.STRING, MONGODB_VALUE_PROJECTION_LIST_DEFAULT, Importance.LOW, MONGODB_VALUE_PROJECTION_LIST_DOC)
                .define(MONGODB_DOCUMENT_ID_STRATEGY_CONF, Type.STRING, MONGODB_DOCUMENT_ID_STRATEGY_DEFAULT, emptyString().or(matching(FULLY_QUALIFIED_CLASS_NAME)), Importance.HIGH, MONGODB_DOCUMENT_ID_STRATEGY_CONF_DOC)
                .define(MONGODB_DOCUMENT_ID_STRATEGIES_CONF, Type.STRING, MONGODB_DOCUMENT_ID_STRATEGIES_DEFAULT, emptyString().or(matching(FULLY_QUALIFIED_CLASS_NAME_LIST)), Importance.LOW, MONGODB_DOCUMENT_ID_STRATEGIES_CONF_DOC)
                .define(MONGODB_KEY_PROJECTION_TYPE_CONF, Type.STRING, MONGODB_KEY_PROJECTION_TYPE_DEFAULT, EnumValidator.in(FieldProjectionTypes.values()), Importance.LOW, MONGODB_KEY_PROJECTION_TYPE_DOC)
                .define(MONGODB_KEY_PROJECTION_LIST_CONF, Type.STRING, MONGODB_KEY_PROJECTION_LIST_DEFAULT, Importance.LOW, MONGODB_KEY_PROJECTION_LIST_DOC)
                .define(MONGODB_FIELD_RENAMER_MAPPING, Type.STRING, MONGODB_FIELD_RENAMER_MAPPING_DEFAULT, Importance.LOW, MONGODB_FIELD_RENAMER_MAPPING_DOC)
                .define(MONGODB_FIELD_RENAMER_REGEXP, Type.STRING, MONGODB_FIELD_RENAMER_REGEXP_DEFAULT, Importance.LOW, MONGODB_FIELD_RENAMER_REGEXP_DOC)
                .define(MONGODB_POST_PROCESSOR_CHAIN, Type.STRING, MONGODB_POST_PROCESSOR_CHAIN_DEFAULT, emptyString().or(matching(FULLY_QUALIFIED_CLASS_NAME_LIST)), Importance.LOW, MONGODB_POST_PROCESSOR_CHAIN_DOC)
                .define(MONGODB_CHANGE_DATA_CAPTURE_HANDLER, Type.STRING, MONGODB_CHANGE_DATA_CAPTURE_HANDLER_DEFAULT, emptyString().or(matching(FULLY_QUALIFIED_CLASS_NAME)), Importance.LOW, MONGODB_CHANGE_DATA_CAPTURE_HANDLER_DOC)
                .define(MONGODB_DELETE_ON_NULL_VALUES, Type.BOOLEAN, MONGODB_DELETE_ON_NULL_VALUES_DEFAULT, Importance.MEDIUM, MONGODB_DELETE_ON_NULL_VALUES_DOC)
                .define(MONGODB_WRITEMODEL_STRATEGY, Type.STRING, MONGODB_WRITEMODEL_STRATEGY_DEFAULT, Importance.LOW, MONGODB_WRITEMODEL_STRATEGY_DOC)
                .define(MONGODB_MAX_BATCH_SIZE, Type.INT, MONGODB_MAX_BATCH_SIZE_DEFAULT, ConfigDef.Range.atLeast(0), Importance.MEDIUM, MONGODB_MAX_BATCH_SIZE_DOC)
                .define(MONGODB_RATE_LIMITING_TIMEOUT, Type.INT, MONGODB_RATE_LIMITING_TIMEOUT_DEFAULT, ConfigDef.Range.atLeast(0), Importance.LOW, MONGODB_RATE_LIMITING_TIMEOUT_DOC)
                .define(MONGODB_RATE_LIMITING_EVERY_N, Type.INT, MONGODB_RATE_LIMITING_EVERY_N_DEFAULT, ConfigDef.Range.atLeast(0), Importance.LOW, MONGODB_RATE_LIMITING_EVERY_N_DOC);
    }

    public ConnectionString getConnectionString() {
        return new ConnectionString(getString(MONGODB_CONNECTION_URI_CONF));
    }

    public boolean isUsingBlacklistValueProjection(final String collection) {
        return getString(MONGODB_VALUE_PROJECTION_TYPE_CONF, collection)
                .equalsIgnoreCase(FieldProjectionTypes.BLACKLIST.name());
    }

    public boolean isUsingWhitelistValueProjection(final String collection) {
        return getString(MONGODB_VALUE_PROJECTION_TYPE_CONF, collection)
                .equalsIgnoreCase(FieldProjectionTypes.WHITELIST.name());
    }

    public boolean isUsingBlacklistKeyProjection(final String collection) {
        return getString(MONGODB_KEY_PROJECTION_TYPE_CONF, collection)
                .equalsIgnoreCase(FieldProjectionTypes.BLACKLIST.name());
    }

    public boolean isUsingWhitelistKeyProjection(final String collection) {
        return getString(MONGODB_KEY_PROJECTION_TYPE_CONF, collection)
                .equalsIgnoreCase(FieldProjectionTypes.WHITELIST.name());
    }

    public Set<String> getKeyProjectionList(final String collection) {
        return buildProjectionList(getString(MONGODB_KEY_PROJECTION_TYPE_CONF, collection),
                getString(MONGODB_KEY_PROJECTION_LIST_CONF, collection)
        );
    }

    public Set<String> getValueProjectionList(final String collection) {
        return buildProjectionList(getString(MONGODB_VALUE_PROJECTION_TYPE_CONF, collection),
                getString(MONGODB_VALUE_PROJECTION_LIST_CONF, collection)
        );
    }

    public Map<String, String> parseRenameFieldnameMappings(final String collection) {
        String settings = getString(MONGODB_FIELD_RENAMER_MAPPING, collection);
        if (settings.isEmpty()) {
            return new HashMap<>();
        }
        try {
            Document allSettings = Document.parse(format("{settings: %s}", settings));
            List<Document> settingsList = allSettings.get("settings", emptyList());
            Map<String, String> map = new HashMap<>();
            for (Document e : settingsList) {
                if (!(e.containsKey("oldName") || e.containsKey("newName"))) {
                    throw new ConfigException(format("Error: parsing rename fieldname mappings failed: %s", settings));
                }
                map.put(e.getString("oldName"), e.getString("newName"));
            }
            return map;
        } catch (JsonParseException e) {
            throw new ConfigException(format("Unable to parse settings invalid Json: %s", settings), e);
        }
    }

    public List<RegExpSettings> parseRenameRegExpSettings(final String collection) {
        try {
            String settings = getString(MONGODB_FIELD_RENAMER_REGEXP, collection);
            if (settings.isEmpty()) {
                return emptyList();
            }

            Document regexDocument = Document.parse(format("{r: %s}", settings));
            List<RegExpSettings> regExpSettings = new ArrayList<>();
            regexDocument.get("r", Collections.<Document>emptyList()).forEach(d -> regExpSettings.add(new RegExpSettings(d)));
            return regExpSettings;
        } catch (Exception e) {
            throw new ConfigException("Error: parsing rename regexp settings failed", e);
        }
    }

    public PostProcessor buildPostProcessorChain(final String collection) {

        Set<String> classes = new LinkedHashSet<>(splitAndTrimAndRemoveConfigListEntries(
                getString(MONGODB_POST_PROCESSOR_CHAIN, collection)));

        //if no post processors are specified
        //DocumentIdAdder is always used since it's mandatory
        if (classes.size() == 0) {
            return new DocumentIdAdder(this, collection);
        }

        PostProcessor first = null;
        if (!classes.contains(DocumentIdAdder.class.getName())) {
            first = new DocumentIdAdder(this, collection);
        }

        PostProcessor next = null;
        for (String clazz : classes) {
            try {
                if (first == null) {
                    first = (PostProcessor) Class.forName(clazz)
                            .getConstructor(MongoDbSinkConnectorConfig.class, String.class)
                            .newInstance(this, collection);
                } else {
                    PostProcessor current = (PostProcessor) Class.forName(clazz)
                            .getConstructor(MongoDbSinkConnectorConfig.class, String.class)
                            .newInstance(this, collection);
                    if (next == null) {
                        first.chain(current);
                        next = current;
                    } else {
                        next = next.chain(current);
                    }
                }
            } catch (ReflectiveOperationException e) {
                throw new ConfigException(e.getMessage(), e);
            } catch (ClassCastException e) {
                throw new ConfigException("Error: specified class " + clazz
                        + " violates the contract since it doesn't extend "
                        + PostProcessor.class.getName());
            }
        }

        return first;
    }

    Map<String, PostProcessor> buildPostProcessorChains() {
        Map<String, PostProcessor> postProcessorChains = new HashMap<>();
        postProcessorChains.put(TOPIC_AGNOSTIC_KEY_NAME, buildPostProcessorChain(""));

        splitAndTrimAndRemoveConfigListEntries(getString(MONGODB_COLLECTIONS_CONF)).forEach(collection ->
                postProcessorChains.put(collection, buildPostProcessorChain(collection)));
        return postProcessorChains;
    }

    private Set<String> buildProjectionList(final String projectionType, final String fieldList) {
        if (projectionType.equalsIgnoreCase(FieldProjectionTypes.NONE.name())) {
            return new HashSet<>();
        }

        if (projectionType.equalsIgnoreCase(FieldProjectionTypes.BLACKLIST.name())) {
            return new HashSet<>(splitAndTrimAndRemoveConfigListEntries(fieldList));
        }

        if (projectionType.equalsIgnoreCase(FieldProjectionTypes.WHITELIST.name())) {
            //NOTE: for sub document notation all left prefix bound paths are created
            //which allows for easy recursion mechanism to whitelist nested doc fields

            HashSet<String> whitelistExpanded = new HashSet<>();
            List<String> fields = splitAndTrimAndRemoveConfigListEntries(fieldList);

            for (String f : fields) {
                String entry = f;
                whitelistExpanded.add(entry);
                while (entry.contains(".")) {
                    entry = entry.substring(0, entry.lastIndexOf("."));
                    if (!entry.isEmpty()) {
                        whitelistExpanded.add(entry);
                    }
                }
            }
            return whitelistExpanded;
        }
        throw new ConfigException("Error: invalid settings for " + projectionType);
    }

    private List<String> splitAndTrimAndRemoveConfigListEntries(final String entries) {
        return Arrays.stream(entries.trim().split(FIELD_LIST_SPLIT_EXPR)).filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }

    boolean isUsingCdcHandler(final String collection) {
        return !getString(MONGODB_CHANGE_DATA_CAPTURE_HANDLER, collection).isEmpty();
    }

    boolean isDeleteOnNullValues(final String collection) {
        return getBoolean(MONGODB_DELETE_ON_NULL_VALUES, collection);
    }

    WriteModelStrategy getWriteModelStrategy(final String collection) {
        String strategyClassName = getString(MONGODB_WRITEMODEL_STRATEGY, collection);
        try {
            return (WriteModelStrategy) Class.forName(strategyClassName)
                    .getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new ConfigException(e.getMessage(), e);
        } catch (ClassCastException e) {
            throw new ConfigException("Error: specified class " + strategyClassName
                    + " violates the contract since it doesn't implement " + WriteModelStrategy.class);
        }
    }

    Map<String, WriteModelStrategy> getWriteModelStrategies() {

        Map<String, WriteModelStrategy> writeModelStrategies = new HashMap<>();

        writeModelStrategies.put(TOPIC_AGNOSTIC_KEY_NAME, getWriteModelStrategy(""));

        splitAndTrimAndRemoveConfigListEntries(getString(MONGODB_COLLECTIONS_CONF))
                .forEach(collection -> writeModelStrategies.put(collection, getWriteModelStrategy(collection)));

        return writeModelStrategies;

    }

    private RateLimitSettings getRateLimitSettings(final String collection) {
        return new RateLimitSettings(this.getInt(MONGODB_RATE_LIMITING_TIMEOUT, collection),
                this.getInt(MONGODB_RATE_LIMITING_EVERY_N, collection));
    }

    Map<String, RateLimitSettings> getRateLimitSettings() {
        Map<String, RateLimitSettings> rateLimitSettings = new HashMap<>();
        rateLimitSettings.put(TOPIC_AGNOSTIC_KEY_NAME, getRateLimitSettings(""));
        splitAndTrimAndRemoveConfigListEntries(getString(MONGODB_COLLECTIONS_CONF))
                .forEach(collection -> rateLimitSettings.put(collection, getRateLimitSettings(collection)));
        return rateLimitSettings;
    }

    private WriteModelStrategy getDeleteOneModelDefaultStrategy(final String collection) {

        //NOTE: DeleteOneModel requires the key document
        //which means that the only reasonable ID generation strategies
        //are those which refer to/operate on the key document.
        //Thus currently this means the IdStrategy must be either:

        //FullKeyStrategy
        //PartialKeyStrategy
        //ProvidedInKeyStrategy

        IdStrategy idStrategy = this.getIdStrategy(collection);

        if (!(idStrategy instanceof FullKeyStrategy) && !(idStrategy instanceof PartialKeyStrategy)
                && !(idStrategy instanceof ProvidedInKeyStrategy)) {
            throw new ConfigException(DeleteOneDefaultStrategy.class.getName() + " can only be applied"
                            + " when the configured IdStrategy is either "
                            + FullKeyStrategy.class.getSimpleName() + " or "
                            + PartialKeyStrategy.class.getSimpleName() + " or "
                            + ProvidedInKeyStrategy.class.getSimpleName()
            );
        }
        return new DeleteOneDefaultStrategy(idStrategy);
    }

    Map<String, WriteModelStrategy> getDeleteOneModelDefaultStrategies() {

        Map<String, WriteModelStrategy> deleteModelStrategies = new HashMap<>();

        if (isDeleteOnNullValues("")) {
            deleteModelStrategies.put(TOPIC_AGNOSTIC_KEY_NAME, getDeleteOneModelDefaultStrategy(""));
        }

        splitAndTrimAndRemoveConfigListEntries(getString(MONGODB_COLLECTIONS_CONF))
                .forEach(collection -> {
                    if (isDeleteOnNullValues(collection)) {
                        deleteModelStrategies.put(collection, getDeleteOneModelDefaultStrategy(collection));
                    }
                });

        return deleteModelStrategies;
    }

    static Set<String> getPredefinedCdcHandlerClassNames() {
        Set<String> cdcHandlers = new HashSet<>();
        cdcHandlers.add(MongoDbHandler.class.getName());
        cdcHandlers.add(RdbmsHandler.class.getName());
        cdcHandlers.add(MysqlHandler.class.getName());
        cdcHandlers.add(PostgresHandler.class.getName());
        return cdcHandlers;
    }

    CdcHandler getCdcHandler(final String collection) {
        Set<String> predefinedCdcHandler = getPredefinedCdcHandlerClassNames();

        String cdcHandler = getString(MONGODB_CHANGE_DATA_CAPTURE_HANDLER, collection);

        if (cdcHandler.isEmpty()) {
            return null;
        }

        if (!predefinedCdcHandler.contains(cdcHandler)) {
            throw new ConfigException("Error: unknown cdc handler " + cdcHandler);
        }

        try {
            return (CdcHandler) Class.forName(cdcHandler)
                    .getConstructor(MongoDbSinkConnectorConfig.class)
                    .newInstance(this);
        } catch (ReflectiveOperationException e) {
            throw new ConfigException(e.getMessage(), e);
        } catch (ClassCastException e) {
            throw new ConfigException("Error: specified class " + cdcHandler
                    + " violates the contract since it doesn't implement "
                    + CdcHandler.class);
        }

    }

    Map<String, CdcHandler> getCdcHandlers() {

        Map<String, CdcHandler> cdcHandlers = new HashMap<>();

        splitAndTrimAndRemoveConfigListEntries(getString(MONGODB_COLLECTIONS_CONF))
                .forEach(collection -> {
                            CdcHandler candidate = cdcHandlers.put(collection, getCdcHandler(collection));
                            if (candidate != null) {
                                cdcHandlers.put(collection, candidate);
                            }
                        }
                );

        return cdcHandlers;

    }

    static Set<String> getPredefinedIdStrategyClassNames() {
        Set<String> strategies = new HashSet<String>();
        strategies.add(BsonOidStrategy.class.getName());
        strategies.add(FullKeyStrategy.class.getName());
        strategies.add(KafkaMetaDataStrategy.class.getName());
        strategies.add(PartialKeyStrategy.class.getName());
        strategies.add(PartialValueStrategy.class.getName());
        strategies.add(ProvidedInKeyStrategy.class.getName());
        strategies.add(ProvidedInValueStrategy.class.getName());
        strategies.add(UuidStrategy.class.getName());
        return strategies;
    }

    public IdStrategy getIdStrategy(final String collection) {
        Set<String> availableIdStrategies = getPredefinedIdStrategyClassNames();

        Set<String> customIdStrategies = new HashSet<>(
                splitAndTrimAndRemoveConfigListEntries(getString(MONGODB_DOCUMENT_ID_STRATEGIES_CONF))
        );

        availableIdStrategies.addAll(customIdStrategies);

        String strategyClassName = getString(MONGODB_DOCUMENT_ID_STRATEGY_CONF, collection);

        if (!availableIdStrategies.contains(strategyClassName)) {
            throw new ConfigException("Error: unknown id strategy " + strategyClassName);
        }

        try {
            if (strategyClassName.equals(PartialKeyStrategy.class.getName())
                    || strategyClassName.equals(PartialValueStrategy.class.getName())) {
                return (IdStrategy) Class.forName(strategyClassName)
                        .getConstructor(FieldProjector.class)
                        .newInstance(this.getKeyProjector(collection));
            }
            return (IdStrategy) Class.forName(strategyClassName)
                    .getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new ConfigException(e.getMessage(), e);
        } catch (ClassCastException e) {
            throw new ConfigException("Error: specified class " + strategyClassName
                    + " violates the contract since it doesn't implement " + IdStrategy.class);
        }
    }

    public FieldProjector getKeyProjector(final String collection) {

        if (getString(MONGODB_KEY_PROJECTION_TYPE_CONF, collection)
                .equalsIgnoreCase(FieldProjectionTypes.BLACKLIST.name())) {

            if (getString(MONGODB_DOCUMENT_ID_STRATEGY_CONF, collection).
                    equals(PartialValueStrategy.class.getName())) {

                return new BlacklistValueProjector(this,
                        this.getKeyProjectionList(collection),
                        cfg -> cfg.isUsingBlacklistKeyProjection(collection), collection);
            }

            if (getString(MONGODB_DOCUMENT_ID_STRATEGY_CONF, collection).
                    equals(PartialKeyStrategy.class.getName())) {

                return new BlacklistKeyProjector(this,
                        this.getKeyProjectionList(collection),
                        cfg -> cfg.isUsingBlacklistKeyProjection(collection), collection);
            }
        }

        if (getString(MONGODB_KEY_PROJECTION_TYPE_CONF, collection)
                .equalsIgnoreCase(FieldProjectionTypes.WHITELIST.name())) {

            if (getString(MONGODB_DOCUMENT_ID_STRATEGY_CONF, collection).
                    equals(PartialValueStrategy.class.getName())) {

                return new WhitelistValueProjector(this,
                        this.getKeyProjectionList(collection),
                        cfg -> cfg.isUsingWhitelistKeyProjection(collection), collection);
            }

            if (getString(MONGODB_DOCUMENT_ID_STRATEGY_CONF, collection).
                    equals(PartialKeyStrategy.class.getName())) {

                return new WhitelistKeyProjector(this,
                        this.getKeyProjectionList(collection),
                        cfg -> cfg.isUsingWhitelistKeyProjection(collection), collection);
            }

        }

        throw new ConfigException("Error: settings invalid for " + MONGODB_KEY_PROJECTION_TYPE_CONF);
    }

    //EnumValidator borrowed from
    //https://github.com/confluentinc/kafka-connect-jdbc/blob/master/src/main/java/io/confluent/connect/jdbc/sink/JdbcSinkConfig.java
    private static final class EnumValidator implements ConfigDef.Validator {
        private final List<String> canonicalValues;
        private final Set<String> validValues;

        private EnumValidator(final List<String> canonicalValues, final Set<String> validValues) {
            this.canonicalValues = canonicalValues;
            this.validValues = validValues;
        }

        public static <E> EnumValidator in(final E[] enumerators) {
            final List<String> canonicalValues = new ArrayList<>(enumerators.length);
            final Set<String> validValues = new HashSet<>(enumerators.length * 2);
            for (E e : enumerators) {
                canonicalValues.add(e.toString().toLowerCase());
                validValues.add(e.toString().toUpperCase());
                validValues.add(e.toString().toLowerCase());
            }
            return new EnumValidator(canonicalValues, validValues);
        }

        @Override
        public void ensureValid(final String key, final Object value) {
            if (!validValues.contains(value)) {
                throw new ConfigException(key, value, "Invalid enumerator");
            }
        }

        @Override
        public String toString() {
            return canonicalValues.toString();
        }
    }

    interface ValidatorWithOperators extends ConfigDef.Validator {

        default ValidatorWithOperators or(final ConfigDef.Validator other) {
            return (name, value) -> {
                try {
                    this.ensureValid(name, value);
                } catch (ConfigException e) {
                    other.ensureValid(name, value);
                }
            };
        }

        default ValidatorWithOperators and(final ConfigDef.Validator other) {
            return (name, value) -> {
                this.ensureValid(name, value);
                other.ensureValid(name, value);
            };
        }
    }

    protected static ValidatorWithOperators emptyString() {
        return (name, value) -> {
            // value type already validated when parsed as String, hence ignoring ClassCastException
            if (!((String) value).isEmpty()) {
                throw new ConfigException(name, value, "Not empty");
            }
        };
    }

    protected static ValidatorWithOperators matching(final Pattern pattern) {
        return (name, value) -> {
            // type already validated when parsing config, hence ignoring ClassCastException
            if (!pattern.matcher((String) value).matches()) {
                throw new ConfigException(name, value, "Does not match: " + pattern);
            }
        };
    }

}