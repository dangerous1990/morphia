/*
 * Copyright (C) 2010 Olafur Gauti Gudmundsson
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */


package org.mongodb.morphia.query;


import com.jayway.awaitility.Awaitility;
import com.mongodb.BasicDBObject;
import com.mongodb.CursorType;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.MongoException;
import com.mongodb.MongoInternalException;
import com.mongodb.ReadPreference;
import com.mongodb.client.model.CollationStrength;
import org.bson.types.CodeWScope;
import org.bson.types.ObjectId;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.mongodb.morphia.Key;
import org.mongodb.morphia.TestBase;
import org.mongodb.morphia.TestDatastore.FacebookUser;
import org.mongodb.morphia.TestDatastore.KeysKeysKeys;
import org.mongodb.morphia.TestMapper.CustomId;
import org.mongodb.morphia.TestMapper.UsesCustomIdObject;
import org.mongodb.morphia.annotations.CappedAt;
import org.mongodb.morphia.annotations.Embedded;
import org.mongodb.morphia.annotations.Entity;
import org.mongodb.morphia.annotations.Id;
import org.mongodb.morphia.annotations.Indexed;
import org.mongodb.morphia.annotations.PrePersist;
import org.mongodb.morphia.annotations.Property;
import org.mongodb.morphia.annotations.Reference;
import org.mongodb.morphia.mapping.Mapper;
import org.mongodb.morphia.testmodel.Hotel;
import org.mongodb.morphia.testmodel.Rectangle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static com.mongodb.client.model.Collation.builder;
import static java.util.Arrays.asList;
import static java.util.Arrays.copyOfRange;
import static java.util.Collections.singletonList;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mongodb.morphia.query.Sort.ascending;
import static org.mongodb.morphia.query.Sort.descending;
import static org.mongodb.morphia.query.Sort.naturalAscending;
import static org.mongodb.morphia.query.Sort.naturalDescending;


/**
 * @author Scott Hernandez
 */
@SuppressWarnings("unchecked")
public class TestQuery extends TestBase {

    @Test
    @SuppressWarnings("deprecation")
    public void batchSize() {
        QueryImpl<Photo> query = (QueryImpl<Photo>) getDs().find(Photo.class)
                                                           .batchSize(42);
        Assert.assertEquals(42, query.getBatchSize());
        Assert.assertEquals(42, query.getOptions().getBatchSize());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void cursorTimeOut() {
        QueryImpl<Photo> query = (QueryImpl<Photo>) getDs().find(Photo.class)
                                                           .enableCursorTimeout();
        Assert.assertFalse(query.getOptions().isNoCursorTimeout());

        query.disableCursorTimeout();
        Assert.assertTrue(query.getOptions().isNoCursorTimeout());
    }

    @Test
    public void genericMultiKeyValueQueries() {
        getMorphia().map(GenericKeyValue.class);
        getDs().ensureIndexes(GenericKeyValue.class);
        final GenericKeyValue<String> value = new GenericKeyValue<String>();
        final List<Object> keys = Arrays.<Object>asList("key1", "key2");
        value.key = keys;
        getDs().save(value);

        final Query<GenericKeyValue> query = getDs().find(GenericKeyValue.class).field("key").hasAnyOf(keys);
        Assert.assertTrue(query.toString().replaceAll("\\s", "").contains("{\"$in\":[\"key1\",\"key2\"]"));
        assertEquals(query.get().id, value.id);
    }

    @Test
    @SuppressWarnings("deprecation")
    public void maxScan() {
        getDs().save(asList(new Pic("pic1"), new Pic("pic2"), new Pic("pic3"), new Pic("pic4")));

        assertEquals(2, getDs().find(Pic.class)
                               .maxScan(2)
                               .asList()
                               .size());
        assertEquals(2, getDs().find(Pic.class)
                               .asList(new FindOptions()
                                           .modifier("$maxScan", 2))
                               .size());
        assertEquals(4, getDs().find(Pic.class).asList().size());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void maxTime() {
        Query<ContainsRenamedFields> query = getDs().find(ContainsRenamedFields.class)
                                                    .maxTime(15, TimeUnit.MINUTES);

        assertEquals(900, ((QueryImpl) query).getMaxTime(TimeUnit.SECONDS));
    }

    @Test
    public void multiKeyValueQueries() {
        getMorphia().map(KeyValue.class);
        getDs().ensureIndexes(KeyValue.class);
        final KeyValue value = new KeyValue();
        final List<Object> keys = Arrays.<Object>asList("key1", "key2");
        value.key = keys;
        getDs().save(value);

        final Query<KeyValue> query = getDs().find(KeyValue.class).field("key").hasAnyOf(keys);
        Assert.assertTrue(query.toString().replaceAll("\\s", "").contains("{\"$in\":[\"key1\",\"key2\"]"));
        assertEquals(query.get().id, value.id);
    }

    @Test
    @SuppressWarnings("deprecation")
    public void oldReadPreference() {
        QueryImpl<Photo> query = (QueryImpl<Photo>) getDs().find(Photo.class)
                                                           .queryNonPrimary();
        Assert.assertEquals(ReadPreference.secondaryPreferred(), query.getOptions().getReadPreference());
        query.queryPrimaryOnly();
        Assert.assertEquals(ReadPreference.primary(), query.getOptions().getReadPreference());
    }

    @Test
    public void referenceKeys() {
        final ReferenceKey key1 = new ReferenceKey("key1");

        getDs().save(asList(key1, new Pic("pic1"), new Pic("pic2"), new Pic("pic3"), new Pic("pic4")));

        final ReferenceKeyValue value = new ReferenceKeyValue();
        value.id = key1;

        final Key<ReferenceKeyValue> key = getDs().save(value);

        final ReferenceKeyValue byKey = getDs().getByKey(ReferenceKeyValue.class, key);
        assertEquals(value.id, byKey.id);
    }

    @Test
    public void snapshot() {
        getDs().find(Photo.class)
               .get(new FindOptions()
                        .modifier("$snapshot", true));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void snapshotOld() {
        QueryImpl<Photo> query = (QueryImpl<Photo>) getDs().find(Photo.class)
                                                           .enableSnapshotMode();
        Assert.assertTrue(query.getOptions().getModifiers().containsField("$snapshot"));
        query.get();

        query.disableSnapshotMode();
        Assert.assertFalse(query.getOptions().getModifiers().containsField("$snapshot"));
    }

    @Override
    @After
    public void tearDown() {
        turnOffProfilingAndDropProfileCollection();
        super.tearDown();
    }

    @Test
    public void testAliasedFieldSort() {
        getDs().save(asList(new Rectangle(1, 10), new Rectangle(3, 8), new Rectangle(6, 10), new Rectangle(10, 10), new Rectangle(10, 1)));

        Rectangle r1 = getDs().find(Rectangle.class)
                              .order("w")
                              .get(new FindOptions()
                                       .limit(1));
        assertNotNull(r1);
        assertEquals(1, r1.getWidth(), 0);

        r1 = getDs().find(Rectangle.class)
                    .order("-w")
                    .get(new FindOptions()
                             .limit(1));
        assertNotNull(r1);
        assertEquals(10, r1.getWidth(), 0);
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testAliasedFieldSortOld() {
        getDs().save(asList(new Rectangle(1, 10), new Rectangle(3, 8), new Rectangle(6, 10), new Rectangle(10, 10), new Rectangle(10, 1)));

        Rectangle r1 = getDs().find(Rectangle.class).limit(1).order("w").get();
        assertNotNull(r1);
        assertEquals(1, r1.getWidth(), 0);

        r1 = getDs().find(Rectangle.class).limit(1).order("-w").get();
        assertNotNull(r1);
        assertEquals(10, r1.getWidth(), 0);
    }

    @Test
    public void testCaseVariants() {
        getDs().save(asList(new Pic("pic1"), new Pic("pic2"), new Pic("pic3"), new Pic("pic4")));

        assertEquals(0, getDs().find(Pic.class)
                               .field("name").contains("PIC")
                               .asList()
                               .size());
        assertEquals(4, getDs().find(Pic.class)
                               .field("name").containsIgnoreCase("PIC")
                               .asList()
                               .size());

        assertEquals(0, getDs().find(Pic.class)
                               .field("name").equal("PIC1")
                               .asList()
                               .size());
        assertEquals(1, getDs().find(Pic.class)
                               .field("name").equalIgnoreCase("PIC1")
                               .asList()
                               .size());

        assertEquals(0, getDs().find(Pic.class)
                               .field("name").endsWith("C1")
                               .asList()
                               .size());
        assertEquals(1, getDs().find(Pic.class)
                               .field("name").endsWithIgnoreCase("C1")
                               .asList()
                               .size());

        assertEquals(0, getDs().find(Pic.class)
                               .field("name").startsWith("PIC")
                               .asList()
                               .size());
        assertEquals(4, getDs().find(Pic.class)
                               .field("name").startsWithIgnoreCase("PIC")
                               .asList()
                               .size());
    }

    @Test
    public void testCollations() {
        checkMinServerVersion(3.4);

        getMorphia().map(ContainsRenamedFields.class);
        getDs().save(asList(new ContainsRenamedFields("first", "last"),
                            new ContainsRenamedFields("First", "Last")));

        Query query = getDs().find(ContainsRenamedFields.class)
                             .field("last_name").equal("last");
        assertEquals(1, query.asList().size());
        assertEquals(2, query.asList(new FindOptions()
                                         .collation(builder()
                                                        .locale("en")
                                                        .collationStrength(CollationStrength.SECONDARY)
                                                        .build()))
                             .size());
        assertEquals(1, query.count());
        assertEquals(2, query.count(new CountOptions()
                                        .collation(builder()
                                                       .locale("en")
                                                       .collationStrength(CollationStrength.SECONDARY)
                                                       .build())));
    }

    @Test
    public void testCombinationQuery() {
        getDs().save(asList(new Rectangle(1, 10), new Rectangle(4, 2), new Rectangle(6, 10), new Rectangle(8, 5), new Rectangle(10, 4)));

        Query<Rectangle> q = getDs().find(Rectangle.class);
        q.and(q.criteria("width").equal(10), q.criteria("height").equal(1));
        assertEquals(1, getDs().getCount(q));

        q = getDs().find(Rectangle.class);
        q.or(q.criteria("width").equal(10), q.criteria("height").equal(10));
        assertEquals(3, getDs().getCount(q));

        q = getDs().find(Rectangle.class);
        q.or(q.criteria("width").equal(10), q.and(q.criteria("width").equal(5), q.criteria("height").equal(8)));
        assertEquals(3, getDs().getCount(q));
    }

    @Test
    public void testCommentsShowUpInLogs() {
        getDs().save(asList(new Pic("pic1"), new Pic("pic2"), new Pic("pic3"), new Pic("pic4")));

        getDb().command(new BasicDBObject("profile", 2));
        String expectedComment = "test comment";

        getDs().find(Pic.class)
               .asList(new FindOptions()
                           .modifier("$comment", expectedComment));

        DBCollection profileCollection = getDb().getCollection("system.profile");
        assertNotEquals(0, profileCollection.count());
        DBObject profileRecord = profileCollection.findOne(new BasicDBObject("op", "query")
                                                               .append("ns", getDs().getCollection(Pic.class).getFullName()));
        assertEquals(profileRecord.toString(), expectedComment, getCommentFromProfileRecord(profileRecord));

        turnOffProfilingAndDropProfileCollection();
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testCommentsShowUpInLogsOld() {
        getDs().save(asList(new Pic("pic1"), new Pic("pic2"), new Pic("pic3"), new Pic("pic4")));

        getDb().command(new BasicDBObject("profile", 2));
        String expectedComment = "test comment";

        getDs().find(Pic.class).comment(expectedComment).asList();

        DBCollection profileCollection = getDb().getCollection("system.profile");
        assertNotEquals(0, profileCollection.count());
        DBObject profileRecord = profileCollection.findOne(new BasicDBObject("op", "query")
                                                               .append("ns", getDs().getCollection(Pic.class).getFullName()));
        assertEquals(profileRecord.toString(), expectedComment, getCommentFromProfileRecord(profileRecord));

        turnOffProfilingAndDropProfileCollection();
    }

    @Test
    public void testComplexElemMatchQuery() {
        Keyword oscar = new Keyword("Oscar", 42);
        getDs().save(new PhotoWithKeywords(oscar, new Keyword("Jim", 12)));
        assertNull(getDs().find(PhotoWithKeywords.class)
                          .field("keywords")
                          .elemMatch(getDs()
                                         .find(Keyword.class)
                                         .filter("keyword = ", "Oscar")
                                         .filter("score = ", 12))
                          .get());

        List<PhotoWithKeywords> keywords = getDs().find(PhotoWithKeywords.class)
                                                  .field("keywords")
                                                  .elemMatch(getDs()
                                                                 .find(Keyword.class)
                                                                 .filter("score > ", 20)
                                                                 .filter("score < ", 100))
                                                  .asList();
        assertEquals(1, keywords.size());
        assertEquals(oscar, keywords.get(0).keywords.get(0));
    }

    @Test
    public void testComplexIdQuery() {
        final CustomId cId = new CustomId();
        cId.setId(new ObjectId());
        cId.setType("banker");

        final UsesCustomIdObject object = new UsesCustomIdObject();
        object.setId(cId);
        object.setText("hllo");
        getDs().save(object);

        assertNotNull(getDs().find(UsesCustomIdObject.class).filter("_id.type", "banker").get());

        assertNotNull(getDs().find(UsesCustomIdObject.class).field("_id").hasAnyOf(singletonList(cId)).get());
    }

    @Test
    public void testComplexIdQueryWithRenamedField() {
        final CustomId cId = new CustomId();
        cId.setId(new ObjectId());
        cId.setType("banker");

        final UsesCustomIdObject object = new UsesCustomIdObject();
        object.setId(cId);
        object.setText("hllo");
        getDs().save(object);

        assertNotNull(getDs().find(UsesCustomIdObject.class).filter("_id.t", "banker").get());
    }

    @Test
    public void testComplexRangeQuery() {
        getDs().save(asList(new Rectangle(1, 10), new Rectangle(4, 2), new Rectangle(6, 10), new Rectangle(8, 5), new Rectangle(10, 4)));

        assertEquals(2, getDs().getCount(getDs().find(Rectangle.class)
                                                .filter("height >", 3)
                                                .filter("height <", 8)));
        assertEquals(1, getDs().getCount(getDs().find(Rectangle.class)
                                                .filter("height >", 3)
                                                .filter("height <", 8)
                                                .filter("width", 10)));
    }

    @Test
    public void testCompoundSort() {
        getDs().save(asList(new Rectangle(1, 10), new Rectangle(3, 8), new Rectangle(6, 10), new Rectangle(10, 10), new Rectangle(10, 1)));

        Rectangle r1 = getDs().find(Rectangle.class).order("width,-height").get();
        assertNotNull(r1);
        assertEquals(1, r1.getWidth(), 0);
        assertEquals(10, r1.getHeight(), 0);

        r1 = getDs().find(Rectangle.class).order("-height,-width").get();
        assertNotNull(r1);
        assertEquals(10, r1.getWidth(), 0);
        assertEquals(10, r1.getHeight(), 0);
    }

    @Test
    public void testCompoundSortWithSortBeans() {
        List<Rectangle> list =
            asList(new Rectangle(1, 10), new Rectangle(3, 8), new Rectangle(6, 10), new Rectangle(10, 10), new Rectangle(10, 1));
        Collections.shuffle(list);
        getDs().save(list);

        compareLists(list,
                     getDs().find(Rectangle.class).order("width,-height"),
                     getDs().find(Rectangle.class).order(ascending("width"), descending("height")),
                     new RectangleComparator());
        compareLists(list,
                     getDs().find(Rectangle.class).order("-height,-width"),
                     getDs().find(Rectangle.class).order(descending("height"), descending("width")),
                     new RectangleComparator1());
        compareLists(list,
                     getDs().find(Rectangle.class).order("width,height"),
                     getDs().find(Rectangle.class).order(ascending("width"), ascending("height")),
                     new RectangleComparator2());
        compareLists(list,
                     getDs().find(Rectangle.class).order("width,height"),
                     getDs().find(Rectangle.class).order("width, height"),
                     new RectangleComparator3());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testCorrectQueryForNotWithSizeEqIssue514() {
        Query<PhotoWithKeywords> query = getAds()
            .find(PhotoWithKeywords.class)
            .field("keywords").not().sizeEq(3);

        assertEquals(new BasicDBObject("keywords", new BasicDBObject("$not", new BasicDBObject("$size", 3))), query.getQueryObject());
    }

    @Test
    public void testDBObjectOrQuery() {
        getDs().save(new PhotoWithKeywords("scott", "hernandez"));

        final List<DBObject> orList = new ArrayList<DBObject>();
        orList.add(new BasicDBObject("keywords.keyword", "scott"));
        orList.add(new BasicDBObject("keywords.keyword", "ralph"));
        final BasicDBObject orQuery = new BasicDBObject("$or", orList);

        Query<PhotoWithKeywords> q = getAds().createQuery(PhotoWithKeywords.class, orQuery);
        assertEquals(1, q.count());

        q = getAds().find(PhotoWithKeywords.class).disableValidation().filter("$or", orList);
        assertEquals(1, q.count());
    }

    @Test
    public void testDeepQuery() {
        getDs().save(new PhotoWithKeywords(new Keyword("california"), new Keyword("nevada"), new Keyword("arizona")));
        assertNotNull(getDs().find(PhotoWithKeywords.class).filter("keywords.keyword", "california").get());
        assertNull(getDs().find(PhotoWithKeywords.class).filter("keywords.keyword", "not").get());
    }

    @Test
    public void testDeepQueryWithBadArgs() {
        getDs().save(new PhotoWithKeywords(new Keyword("california"), new Keyword("nevada"), new Keyword("arizona")));
        assertNull(getDs().find(PhotoWithKeywords.class).filter("keywords.keyword", 1).get());
        assertNull(getDs().find(PhotoWithKeywords.class).filter("keywords.keyword", "california".getBytes()).get());
        assertNull(getDs().find(PhotoWithKeywords.class).filter("keywords.keyword", null).get());
    }

    @Test
    public void testDeepQueryWithRenamedFields() {
        getDs().save(new PhotoWithKeywords(new Keyword("california"), new Keyword("nevada"), new Keyword("arizona")));
        assertNotNull(getDs().find(PhotoWithKeywords.class).filter("keywords.keyword", "california").get());
        assertNull(getDs().find(PhotoWithKeywords.class).filter("keywords.keyword", "not").get());
    }

    @Test
    public void testDeleteQuery() {
        getDs().save(asList(new Rectangle(1, 10),
                            new Rectangle(1, 10),
                            new Rectangle(1, 10),
                            new Rectangle(10, 10),
                            new Rectangle(10, 10)));

        assertEquals(5, getDs().getCount(Rectangle.class));
        getDs().delete(getDs().find(Rectangle.class).filter("height", 1D));
        assertEquals(2, getDs().getCount(Rectangle.class));
    }

    @Test
    public void testElemMatchQuery() {
        getDs().save(asList(new PhotoWithKeywords(), new PhotoWithKeywords("Scott", "Joe", "Sarah")));
        assertNotNull(getDs().find(PhotoWithKeywords.class)
                             .field("keywords").elemMatch(getDs().find(Keyword.class).filter("keyword", "Scott"))
                             .get());
        assertNull(getDs().find(PhotoWithKeywords.class)
                          .field("keywords").elemMatch(getDs().find(Keyword.class).filter("keyword", "Randy"))
                          .get());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testElemMatchQueryOld() {
        getDs().save(asList(new PhotoWithKeywords(), new PhotoWithKeywords("Scott", "Joe", "Sarah")));
        assertNotNull(getDs().find(PhotoWithKeywords.class)
                             .field("keywords")
                             .hasThisElement(new Keyword("Scott"))
                             .get());
        // TODO add back when $and is done (> 1.5)  this needs multiple $elemMatch clauses
        //        query = getDs().find(PhotoWithKeywords.class)
        //                       .field("keywords")
        //                       .hasThisElement(new Keyword[]{new Keyword("Scott"), new Keyword("Joe")});
        //        System.out.println("************ query = " + query);
        //        PhotoWithKeywords pwkScottSarah = query.get();
        //        assertNotNull(pwkScottSarah);
        assertNull(getDs().find(PhotoWithKeywords.class)
                          .field("keywords")
                          .hasThisElement(new Keyword("Randy"))
                          .get());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testElemMatchVariants() {
        final PhotoWithKeywords pwk1 = new PhotoWithKeywords();
        final PhotoWithKeywords pwk2 = new PhotoWithKeywords("Kevin");
        final PhotoWithKeywords pwk3 = new PhotoWithKeywords("Scott", "Joe", "Sarah");
        final PhotoWithKeywords pwk4 = new PhotoWithKeywords(new Keyword("Scott", 14));

        Iterator<Key<PhotoWithKeywords>> iterator = getDs().save(asList(pwk1, pwk2, pwk3, pwk4)).iterator();
        Key<PhotoWithKeywords> key1 = iterator.next();
        Key<PhotoWithKeywords> key2 = iterator.next();
        Key<PhotoWithKeywords> key3 = iterator.next();
        Key<PhotoWithKeywords> key4 = iterator.next();

        assertEquals(asList(key3, key4), getDs().find(PhotoWithKeywords.class)
                                                .field("keywords")
                                                .hasThisElement(new Keyword("Scott"))
                                                .asKeyList());

        assertEquals(asList(key3, key4), getDs().find(PhotoWithKeywords.class)
                                                .field("keywords")
                                                .elemMatch(getDs()
                                                               .find(Keyword.class)
                                                               .field("keyword").equal("Scott"))
                                                .asKeyList());

        assertEquals(singletonList(key4), getDs().find(PhotoWithKeywords.class)
                                                 .field("keywords")
                                                 .hasThisElement(new Keyword(14))
                                                 .asKeyList());

        assertEquals(singletonList(key4), getDs().find(PhotoWithKeywords.class)
                                                 .field("keywords")
                                                 .elemMatch(getDs()
                                                                .find(Keyword.class)
                                                                .field("score").equal(14))
                                                 .asKeyList());

        assertEquals(asList(key1, key2), getDs().find(PhotoWithKeywords.class)
                                                .field("keywords")
                                                .doesNotHaveThisElement(new Keyword("Scott"))
                                                .asKeyList());

        assertEquals(asList(key1, key2), getDs().find(PhotoWithKeywords.class)
                                                .field("keywords").not()
                                                .elemMatch(getDs()
                                                               .find(Keyword.class)
                                                               .field("keyword").equal("Scott"))
                                                .asKeyList());
    }

    @Test
    public void testExplainPlan() {
        getDs().save(asList(new Pic("pic1"), new Pic("pic2"), new Pic("pic3"), new Pic("pic4")));
        Map<String, Object> explainResult = getDs().find(Pic.class).explain();
        assertEquals(explainResult.toString(), 4, serverIsAtMostVersion(2.7)
                                                  ? explainResult.get("n")
                                                  : ((Map) explainResult.get("executionStats")).get("nReturned"));
    }

    @Test
    public void testFetchEmptyEntities() {
        PhotoWithKeywords pwk1 = new PhotoWithKeywords("california", "nevada", "arizona");
        PhotoWithKeywords pwk2 = new PhotoWithKeywords("Joe", "Sarah");
        PhotoWithKeywords pwk3 = new PhotoWithKeywords("MongoDB", "World");
        getDs().save(asList(pwk1, pwk2, pwk3));

        MorphiaIterator<PhotoWithKeywords, PhotoWithKeywords> keys = getDs().find(PhotoWithKeywords.class).fetchEmptyEntities();
        assertTrue(keys.hasNext());
        assertEquals(pwk1.id, keys.next().id);
        assertEquals(pwk2.id, keys.next().id);
        assertEquals(pwk3.id, keys.next().id);
    }

    @Test
    public void testFetchKeys() {
        PhotoWithKeywords pwk1 = new PhotoWithKeywords("california", "nevada", "arizona");
        PhotoWithKeywords pwk2 = new PhotoWithKeywords("Joe", "Sarah");
        PhotoWithKeywords pwk3 = new PhotoWithKeywords("MongoDB", "World");
        getDs().save(asList(pwk1, pwk2, pwk3));

        MorphiaKeyIterator<PhotoWithKeywords> keys = getDs().find(PhotoWithKeywords.class).fetchKeys();
        assertTrue(keys.hasNext());
        assertEquals(pwk1.id, keys.next().getId());
        assertEquals(pwk2.id, keys.next().getId());
        assertEquals(pwk3.id, keys.next().getId());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testFluentAndOrQuery() {
        getDs().save(new PhotoWithKeywords("scott", "hernandez"));

        final Query<PhotoWithKeywords> q = getAds().find(PhotoWithKeywords.class);
        q.and(
            q.or(q.criteria("keywords.keyword").equal("scott")),
            q.or(q.criteria("keywords.keyword").equal("hernandez")));

        assertEquals(1, q.count());
        assertTrue(q.getQueryObject().containsField("$and"));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testFluentAndQuery1() {
        getDs().save(new PhotoWithKeywords("scott", "hernandez"));

        final Query<PhotoWithKeywords> q = getAds().find(PhotoWithKeywords.class);
        q.and(q.criteria("keywords.keyword").hasThisOne("scott"),
              q.criteria("keywords.keyword").hasAnyOf(asList("scott", "hernandez")));

        assertEquals(1, q.count());
        assertTrue(q.getQueryObject().containsField("$and"));

    }

    @Test
    public void testFluentNotQuery() {
        final PhotoWithKeywords pwk = new PhotoWithKeywords("scott", "hernandez");
        getDs().save(pwk);

        final Query<PhotoWithKeywords> query = getAds().find(PhotoWithKeywords.class);
        query.criteria("keywords.keyword").not().startsWith("ralph");

        assertEquals(1, query.count());
    }

    @Test
    public void testFluentOrQuery() {
        final PhotoWithKeywords pwk = new PhotoWithKeywords("scott", "hernandez");
        getDs().save(pwk);

        final Query<PhotoWithKeywords> q = getAds().find(PhotoWithKeywords.class);
        q.or(
            q.criteria("keywords.keyword").equal("scott"),
            q.criteria("keywords.keyword").equal("ralph"));

        assertEquals(1, q.count());
    }

    @Test
    public void testGetByKeysHetero() {
        final Iterable<Key<Object>> keys = getDs().save(asList(new FacebookUser(1, "scott"), new Rectangle(1, 1)));
        final List<Object> entities = getDs().getByKeys(keys);
        assertNotNull(entities);
        assertEquals(2, entities.size());
        int userCount = 0;
        int rectCount = 0;
        for (final Object o : entities) {
            if (o instanceof Rectangle) {
                rectCount++;
            } else if (o instanceof FacebookUser) {
                userCount++;
            }
        }
        assertEquals(1, rectCount);
        assertEquals(1, userCount);
    }

    @Test
    public void testIdFieldNameQuery() {
        getDs().save(new PhotoWithKeywords("scott", "hernandez"));

        assertNotNull(getDs().find(PhotoWithKeywords.class).filter("id !=", "scott").get());
    }

    @Test
    public void testIdRangeQuery() {
        getDs().save(asList(new HasIntId(1), new HasIntId(11), new HasIntId(12)));
        assertEquals(2, getDs().find(HasIntId.class).filter("_id >", 5).filter("_id <", 20).count());
        assertEquals(1, getDs().find(HasIntId.class).field("_id").greaterThan(0).field("_id").lessThan(11).count());
    }

    @Test
    public void testInQuery() {
        getDs().save(new Photo(asList("red", "green", "blue")));

        assertNotNull(getDs()
                          .find(Photo.class)
                          .field("keywords").in(asList("red", "yellow"))
                          .get());
    }

    @Test
    public void testInQueryWithObjects() {
        getDs().save(asList(new PhotoWithKeywords(), new PhotoWithKeywords("Scott", "Joe", "Sarah")));

        final Query<PhotoWithKeywords> query = getDs()
            .find(PhotoWithKeywords.class)
            .field("keywords").in(asList(new Keyword("Scott"), new Keyword("Randy")));
        assertNotNull(query.get());
    }

    @Test
    public void testKeyList() {
        final Rectangle rect = new Rectangle(1000, 1);
        final Key<Rectangle> rectKey = getDs().save(rect);

        assertEquals(rectKey.getId(), rect.getId());

        final FacebookUser fbUser1 = new FacebookUser(1, "scott");
        final FacebookUser fbUser2 = new FacebookUser(2, "tom");
        final FacebookUser fbUser3 = new FacebookUser(3, "oli");
        final FacebookUser fbUser4 = new FacebookUser(4, "frank");
        final Iterable<Key<FacebookUser>> fbKeys = getDs().save(asList(fbUser1, fbUser2, fbUser3, fbUser4));
        assertEquals(1, fbUser1.getId());

        final List<Key<FacebookUser>> fbUserKeys = new ArrayList<Key<FacebookUser>>();
        for (final Key<FacebookUser> key : fbKeys) {
            fbUserKeys.add(key);
        }

        assertEquals(fbUser1.getId(), fbUserKeys.get(0).getId());
        assertEquals(fbUser2.getId(), fbUserKeys.get(1).getId());
        assertEquals(fbUser3.getId(), fbUserKeys.get(2).getId());
        assertEquals(fbUser4.getId(), fbUserKeys.get(3).getId());

        final KeysKeysKeys k1 = new KeysKeysKeys(rectKey, fbUserKeys);
        final Key<KeysKeysKeys> k1Key = getDs().save(k1);
        assertEquals(k1.getId(), k1Key.getId());

        final KeysKeysKeys k1Loaded = getDs().get(k1);
        for (final Key<FacebookUser> key : k1Loaded.getUsers()) {
            assertNotNull(key.getId());
        }

        assertNotNull(k1Loaded.getRect().getId());
    }

    @Test
    public void testKeyListLookups() {
        final FacebookUser fbUser1 = new FacebookUser(1, "scott");
        final FacebookUser fbUser2 = new FacebookUser(2, "tom");
        final FacebookUser fbUser3 = new FacebookUser(3, "oli");
        final FacebookUser fbUser4 = new FacebookUser(4, "frank");
        final Iterable<Key<FacebookUser>> fbKeys = getDs().save(asList(fbUser1, fbUser2, fbUser3, fbUser4));
        assertEquals(1, fbUser1.getId());

        final List<Key<FacebookUser>> fbUserKeys = new ArrayList<Key<FacebookUser>>();
        for (final Key<FacebookUser> key : fbKeys) {
            fbUserKeys.add(key);
        }

        assertEquals(fbUser1.getId(), fbUserKeys.get(0).getId());
        assertEquals(fbUser2.getId(), fbUserKeys.get(1).getId());
        assertEquals(fbUser3.getId(), fbUserKeys.get(2).getId());
        assertEquals(fbUser4.getId(), fbUserKeys.get(3).getId());

        final KeysKeysKeys k1 = new KeysKeysKeys(null, fbUserKeys);
        final Key<KeysKeysKeys> k1Key = getDs().save(k1);
        assertEquals(k1.getId(), k1Key.getId());

        final KeysKeysKeys k1Reloaded = getDs().get(k1);
        final KeysKeysKeys k1Loaded = getDs().getByKey(KeysKeysKeys.class, k1Key);
        assertNotNull(k1Reloaded);
        assertNotNull(k1Loaded);
        for (final Key<FacebookUser> key : k1Loaded.getUsers()) {
            assertNotNull(key.getId());
        }

        assertEquals(4, k1Loaded.getUsers().size());

        final List<FacebookUser> fbUsers = getDs().getByKeys(FacebookUser.class, k1Loaded.getUsers());
        assertEquals(4, fbUsers.size());
        for (final FacebookUser fbUser : fbUsers) {
            assertNotNull(fbUser);
            assertNotNull(fbUser.getId());
            assertNotNull(fbUser.getUsername());
        }
    }

    @Test
    public void testMixedProjection() {
        getDs().save(new ContainsRenamedFields("Frank", "Zappa"));

        try {
            getDs().find(ContainsRenamedFields.class)
                   .project("first_name", true)
                   .project("last_name", false);
            fail("An exception should have been thrown indication a mixed projection");
        } catch (ValidationException e) {
            // all good
        }

        try {
            getDs().find(ContainsRenamedFields.class)
                   .project("first_name", true)
                   .project("last_name", true)
                   .project("_id", false);
        } catch (ValidationException e) {
            fail("An exception should not have been thrown indication a mixed projection because _id suppression is a special case");
        }

        try {
            getDs().find(ContainsRenamedFields.class)
                   .project("first_name", false)
                   .project("last_name", false)
                   .project("_id", true);
            fail("An exception should have been thrown indication a mixed projection");
        } catch (ValidationException e) {
            // all good
        }

        try {
            getDs().find(IntVector.class)
                   .project("name", false)
                   .project("scalars", new ArraySlice(5));
            fail("An exception should have been thrown indication a mixed projection");
        } catch (ValidationException e) {
            // all good
        }
    }

    @Test
    public void testMultipleConstraintsOnOneField() {
        checkMinServerVersion(3.0);
        getMorphia().map(ContainsPic.class);
        getDs().ensureIndexes();
        Query<ContainsPic> query = getDs().find(ContainsPic.class);
        query.field("size").greaterThanOrEq(10);
        query.field("size").lessThan(100);

        Map<String, Object> explain = query.explain();
        Map<String, Object> queryPlanner = (Map<String, Object>) explain.get("queryPlanner");
        Map<String, Object> winningPlan = (Map<String, Object>) queryPlanner.get("winningPlan");
        Map<String, Object> inputStage = (Map<String, Object>) winningPlan.get("inputStage");
        assertEquals("IXSCAN", inputStage.get("stage"));
    }

    @Test
    public void testNaturalSortAscending() {
        getDs().save(asList(new Rectangle(6, 10), new Rectangle(3, 8), new Rectangle(10, 10), new Rectangle(10, 1)));

        List<Rectangle> results = getDs().find(Rectangle.class).order(naturalAscending()).asList();

        assertEquals(4, results.size());

        Rectangle r;

        r = results.get(0);
        assertNotNull(r);
        assertEquals(6, r.getHeight(), 0);
        assertEquals(10, r.getWidth(), 0);

        r = results.get(1);
        assertNotNull(r);
        assertEquals(3, r.getHeight(), 0);
        assertEquals(8, r.getWidth(), 0);

        r = results.get(2);
        assertNotNull(r);
        assertEquals(10, r.getHeight(), 0);
        assertEquals(10, r.getWidth(), 0);
    }

    @Test
    public void testNaturalSortDescending() {
        getDs().save(asList(new Rectangle(6, 10), new Rectangle(3, 8), new Rectangle(10, 10), new Rectangle(10, 1)));

        List<Rectangle> results = getDs().find(Rectangle.class).order(naturalDescending()).asList();

        assertEquals(4, results.size());

        Rectangle r;

        r = results.get(0);
        assertNotNull(r);
        assertEquals(10, r.getHeight(), 0);
        assertEquals(1, r.getWidth(), 0);

        r = results.get(1);
        assertNotNull(r);
        assertEquals(10, r.getHeight(), 0);
        assertEquals(10, r.getWidth(), 0);

        r = results.get(2);
        assertNotNull(r);
        assertEquals(3, r.getHeight(), 0);
        assertEquals(8, r.getWidth(), 0);
    }

    @Test
    public void testNegativeBatchSize() {
        getDs().delete(getDs().find(PhotoWithKeywords.class));
        getDs().save(asList(new PhotoWithKeywords("scott", "hernandez"),
                            new PhotoWithKeywords("scott", "hernandez"),
                            new PhotoWithKeywords("scott", "hernandez"),
                            new PhotoWithKeywords("1", "2"),
                            new PhotoWithKeywords("3", "4"),
                            new PhotoWithKeywords("5", "6")));
        assertEquals(2, getDs().find(PhotoWithKeywords.class)
                               .asList(new FindOptions()
                                           .batchSize(-2))
                               .size());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testNegativeBatchSizeOld() {
        getDs().delete(getDs().find(PhotoWithKeywords.class));
        getDs().save(asList(new PhotoWithKeywords("scott", "hernandez"),
                            new PhotoWithKeywords("scott", "hernandez"),
                            new PhotoWithKeywords("scott", "hernandez"),
                            new PhotoWithKeywords("1", "2"),
                            new PhotoWithKeywords("3", "4"),
                            new PhotoWithKeywords("5", "6")));
        assertEquals(2, getDs().find(PhotoWithKeywords.class)
                               .batchSize(-2)
                               .asList()
                               .size());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testNoLifeCycleEventsOnParameters() throws Exception {
        final ContainsPic cpk = new ContainsPic();
        final Pic p = new Pic("some pic");
        getDs().save(p);
        cpk.setPic(p);
        getDs().save(cpk);

        Pic queryPic = new Pic("some pic");
        queryPic.setId(p.getId());
        Query query = getDs().find(ContainsPic.class)
                             .field("pic").equal(queryPic);
        assertFalse(queryPic.isPrePersist());
        assertNotNull(query.get());

        getDs().find(ContainsPic.class)
               .field("pic").hasThisElement(queryPic);
        assertFalse(queryPic.isPrePersist());
    }

    @Test
    public void testNonSnapshottedQuery() {
        getDs().delete(getDs().find(PhotoWithKeywords.class));
        getDs().save(asList(new PhotoWithKeywords("scott", "hernandez"),
                            new PhotoWithKeywords("scott", "hernandez"),
                            new PhotoWithKeywords("scott", "hernandez")));
        final Iterator<PhotoWithKeywords> it = getDs().find(PhotoWithKeywords.class)
                                                      .fetch(new FindOptions()
                                                                 .modifier("$snapshot", true)
                                                                 .batchSize(2)
                                                            );
        getDs().save(asList(new PhotoWithKeywords("1", "2"),
                            new PhotoWithKeywords("3", "4"),
                            new PhotoWithKeywords("5", "6")));

        assertNotNull(it.next());
        assertNotNull(it.next());
        //okay, now we should getMore...
        assertTrue(it.hasNext());
        assertNotNull(it.next());
        assertTrue(it.hasNext());
        assertNotNull(it.next());
    }

    @Test
    public void testNonexistentFindGet() {
        assertNull(getDs().find(Hotel.class).filter("_id", -1).get());
    }

    @Test
    public void testNonexistentGet() {
        assertNull(getDs().get(Hotel.class, -1));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testNotGeneratesCorrectQueryForGreaterThan() {
        final Query<Keyword> query = getDs().find(Keyword.class);
        query.criteria("score").not().greaterThan(7);
        assertEquals(new BasicDBObject("score", new BasicDBObject("$not", new BasicDBObject("$gt", 7))), query.getQueryObject());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testNotGeneratesCorrectQueryForRegex() {
        final Query<PhotoWithKeywords> query = getAds().find(PhotoWithKeywords.class);
        query.criteria("keywords.keyword").not().startsWith("ralph");
        DBObject queryObject = query.getQueryObject();
        BasicDBObject expected = new BasicDBObject("keywords.keyword",
                                                   new BasicDBObject("$not", new BasicDBObject("$regex", "^ralph")));
        assertEquals(expected.toString(), queryObject.toString());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testProject() {
        getDs().save(new ContainsRenamedFields("Frank", "Zappa"));

        ContainsRenamedFields found = getDs().find(ContainsRenamedFields.class)
                                             .project("first_name", true)
                                             .get();
        assertNotNull(found.firstName);
        assertNull(found.lastName);

        found = getDs().find(ContainsRenamedFields.class)
                       .project("firstName", true)
                       .get();
        assertNotNull(found.firstName);
        assertNull(found.lastName);

        try {
            getDs()
                .find(ContainsRenamedFields.class)
                .project("bad field name", true)
                .get();
            fail("Validation should have caught the bad field");
        } catch (ValidationException e) {
            // success!
        }

        DBObject fields = getDs()
            .find(ContainsRenamedFields.class)
            .project("_id", true)
            .project("first_name", true)
            .getFieldsObject();
        assertNull(fields.get(Mapper.CLASS_NAME_FIELDNAME));
    }

    @Test
    public void testProjectArrayField() {
        int[] ints = {0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 26, 28, 30};
        IntVector vector = new IntVector(ints);
        getDs().save(vector);

        assertArrayEquals(copy(ints, 0, 4), getDs().find(IntVector.class)
                                                   .project("scalars", new ArraySlice(4))
                                                   .get().scalars);
        assertArrayEquals(copy(ints, 5, 4), getDs().find(IntVector.class)
                                                   .project("scalars", new ArraySlice(5, 4))
                                                   .get().scalars);
        assertArrayEquals(copy(ints, ints.length - 10, 6), getDs().find(IntVector.class)
                                                                  .project("scalars", new ArraySlice(-10, 6))
                                                                  .get().scalars);
        assertArrayEquals(copy(ints, ints.length - 12, 12), getDs().find(IntVector.class)
                                                                   .project("scalars", new ArraySlice(-12))
                                                                   .get().scalars);
    }

    @Test
    public void testQBE() {
        final CustomId cId = new CustomId();
        cId.setId(new ObjectId());
        cId.setType("banker");

        final UsesCustomIdObject object = new UsesCustomIdObject();
        object.setId(cId);
        object.setText("hllo");
        getDs().save(object);
        final UsesCustomIdObject loaded;

        // Add back if/when query by example for embedded fields is supported (require dotting each field).
        // CustomId exId = new CustomId();
        // exId.type = cId.type;
        // loaded = getDs().find(UsesCustomIdObject.class, "_id", exId).get();
        // assertNotNull(loaded);

        final UsesCustomIdObject ex = new UsesCustomIdObject();
        ex.setText(object.getText());
        loaded = getDs().queryByExample(ex).get();
        assertNotNull(loaded);
    }

    @Test
    public void testQueryCount() {
        getDs().save(asList(new Rectangle(1, 10),
                            new Rectangle(1, 10),
                            new Rectangle(1, 10),
                            new Rectangle(10, 10),
                            new Rectangle(10, 10)));

        assertEquals(3, getDs().getCount(getDs().find(Rectangle.class).filter("height", 1D)));
        assertEquals(2, getDs().getCount(getDs().find(Rectangle.class).filter("height", 10D)));
        assertEquals(5, getDs().getCount(getDs().find(Rectangle.class).filter("width", 10D)));

    }

    @Test
    public void testQueryOverLazyReference() {
        final ContainsPic cpk = new ContainsPic();
        final Pic p = new Pic();
        getDs().save(p);
        cpk.lazyPic = p;

        getDs().save(cpk);

        assertEquals(1, getDs().find(ContainsPic.class)
                               .field("lazyPic").equal(p)
                               .asList()
                               .size());
    }

    @Test
    public void testQueryOverReference() {

        final ContainsPic cpk = new ContainsPic();
        final Pic p = new Pic();
        getDs().save(p);
        cpk.pic = p;

        getDs().save(cpk);

        final Query<ContainsPic> query = getDs().find(ContainsPic.class);

        assertEquals(1, query.field("pic").equal(p).asList().size());

        try {
            getDs().find(ContainsPic.class).filter("pic.name", "foo").get();
            assertNull("query validation should have thrown an exception");
        } catch (ValidationException e) {
            assertTrue(e.getMessage().contains("Cannot use dot-"));
        }
    }

    @Test
    public void testRangeQuery() {
        getDs().save(asList(new Rectangle(1, 10), new Rectangle(4, 2), new Rectangle(6, 10), new Rectangle(8, 5), new Rectangle(10, 4)));

        assertEquals(4, getDs().getCount(getDs().find(Rectangle.class).filter("height >", 3)));
        assertEquals(3, getDs().getCount(getDs().find(Rectangle.class).filter("height >", 3).filter("height <", 10)));
        assertEquals(1, getDs().getCount(getDs().find(Rectangle.class).filter("height >", 9).filter("width <", 5)));
        assertEquals(3, getDs().getCount(getDs().find(Rectangle.class).filter("height <", 7)));
    }

    @Test(expected = ValidationException.class)
    public void testReferenceQuery() {
        final Photo p = new Photo();
        final ContainsPhotoKey cpk = new ContainsPhotoKey();
        cpk.photo = getDs().save(p);
        getDs().save(cpk);

        assertNotNull(getDs().find(ContainsPhotoKey.class).filter("photo", p).get());
        assertNotNull(getDs().find(ContainsPhotoKey.class).filter("photo", cpk.photo).get());
        assertNull(getDs().find(ContainsPhotoKey.class).filter("photo", 1).get());

        getDs().find(ContainsPhotoKey.class).filter("photo.keywords", "foo").get();
    }

    @Test
    public void testRegexInsensitiveQuery() {
        getDs().save(new PhotoWithKeywords(new Keyword("california"), new Keyword("nevada"), new Keyword("arizona")));
        final Pattern p = Pattern.compile("(?i)caLifornia");
        assertNotNull(getDs().find(PhotoWithKeywords.class).disableValidation().filter("keywords.keyword", p).get());
        assertNull(getDs().find(PhotoWithKeywords.class).filter("keywords.keyword", Pattern.compile("blah")).get());
    }

    @Test
    public void testRegexQuery() {
        getDs().save(new PhotoWithKeywords(new Keyword("california"), new Keyword("nevada"), new Keyword("arizona")));
        assertNotNull(getDs().find(PhotoWithKeywords.class)
                             .disableValidation()
                             .filter("keywords.keyword", Pattern.compile("california"))
                             .get());
        assertNull(getDs().find(PhotoWithKeywords.class).filter("keywords.keyword", Pattern.compile("blah")).get());
    }

    @Test
    public void testRenamedFieldQuery() {
        getDs().save(new ContainsRenamedFields("Scott", "Bakula"));

        assertNotNull(getDs().find(ContainsRenamedFields.class).field("firstName").equal("Scott").get());
        assertNotNull(getDs().find(ContainsRenamedFields.class).field("first_name").equal("Scott").get());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testRetrievedFields() {
        getDs().save(new ContainsRenamedFields("Frank", "Zappa"));

        ContainsRenamedFields found = getDs()
            .find(ContainsRenamedFields.class)
            .retrievedFields(true, "first_name")
            .get();
        assertNotNull(found.firstName);
        assertNull(found.lastName);

        found = getDs()
            .find(ContainsRenamedFields.class)
            .retrievedFields(true, "firstName")
            .get();
        assertNotNull(found.firstName);
        assertNull(found.lastName);

        try {
            getDs()
                .find(ContainsRenamedFields.class)
                .retrievedFields(true, "bad field name")
                .get();
            fail("Validation should have caught the bad field");
        } catch (ValidationException e) {
            // success!
        }

        DBObject fields = getDs()
            .find(ContainsRenamedFields.class)
            .retrievedFields(true, "_id", "first_name").getFieldsObject();
        assertNull(fields.get(Mapper.CLASS_NAME_FIELDNAME));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testReturnOnlyIndexedFields() {
        getDs().save(asList(new Pic("pic1"), new Pic("pic2"), new Pic("pic3"), new Pic("pic4")));
        getDs().ensureIndex(Pic.class, "name");

        // When
        // find a document by using a search on the field in the index

        // Then
        Pic foundItem = getDs().find(Pic.class)
                               .returnKey()
                               .field("name").equal("pic2")
                               .get();
        assertNotNull(foundItem);
        assertThat("Name should be populated", foundItem.getName(), is("pic2"));
        assertNull("ID should not be populated", foundItem.getId());
    }

    @Test
    public void testSimpleSort() {
        getDs().save(asList(new Rectangle(1, 10), new Rectangle(3, 8), new Rectangle(6, 10), new Rectangle(10, 10), new Rectangle(10, 1)));

        Rectangle r1 = getDs().find(Rectangle.class)
                              .order("width")
                              .get();
        assertNotNull(r1);
        assertEquals(1, r1.getWidth(), 0);

        r1 = getDs().find(Rectangle.class)
                    .order("-width")
                    .get();
        assertNotNull(r1);
        assertEquals(10, r1.getWidth(), 0);
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testSizeEqQuery() {
        assertEquals(new BasicDBObject("keywords", new BasicDBObject("$size", 3)), getDs().find(PhotoWithKeywords.class)
                                                                                          .field("keywords")
                                                                                          .sizeEq(3).getQueryObject());
    }

    @Test
    public void testSnapshottedQuery() {
        getDs().delete(getDs().find(PhotoWithKeywords.class));
        getDs().save(asList(new PhotoWithKeywords("scott", "hernandez"),
                            new PhotoWithKeywords("scott", "hernandez"),
                            new PhotoWithKeywords("scott", "hernandez")));
        final Iterator<PhotoWithKeywords> it = getDs().find(PhotoWithKeywords.class)
                                                      .filter("keywords.keyword", "scott")
                                                      .fetch(new FindOptions()
                                                                 .modifier("$snapshot", true)
                                                                 .batchSize(2));
        getDs().save(asList(new PhotoWithKeywords("1", "2"),
                            new PhotoWithKeywords("3", "4"),
                            new PhotoWithKeywords("5", "6")));

        assertNotNull(it.next());
        assertNotNull(it.next());
        //okay, now we should getMore...
        assertTrue(it.hasNext());
        assertNotNull(it.next());
        assertTrue(!it.hasNext());
    }

    @Test
    public void testStartsWithQuery() {
        getDs().save(new Photo());
        Photo p = getDs().find(Photo.class).field("keywords").startsWith("amaz").get();
        assertNotNull(p);
        p = getDs().find(Photo.class).field("keywords").startsWith("notareal").get();
        assertNull(p);

    }

    @Test
    public void testTailableCursors() {
        getMorphia().map(CappedPic.class);
        getDs().ensureCaps();
        final Query<CappedPic> query = getDs().find(CappedPic.class);
        final List<CappedPic> found = new ArrayList<CappedPic>();
        final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);

        assertEquals(0, query.count());

        executorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                getDs().save(new CappedPic(System.currentTimeMillis() + ""));
            }
        }, 0, 500, TimeUnit.MILLISECONDS);

        final Iterator<CappedPic> tail = query
            .fetch(new FindOptions()
                       .cursorType(CursorType.Tailable));
        Awaitility
            .await()
            .pollDelay(500, TimeUnit.MILLISECONDS)
            .atMost(10, TimeUnit.SECONDS)
            .until(new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    if (tail.hasNext()) {
                        found.add(tail.next());
                    }
                    return found.size() >= 10;
                }
            });
        executorService.shutdownNow();
        Assert.assertTrue(query.count() >= 10);
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testThatElemMatchQueriesOnlyChecksRequiredFields() {
        final PhotoWithKeywords pwk1 = new PhotoWithKeywords(new Keyword("california"), new Keyword("nevada"), new Keyword("arizona"));
        final PhotoWithKeywords pwk2 = new PhotoWithKeywords("Joe", "Sarah");
        pwk2.keywords.add(new Keyword("Scott", 14));

        getDs().save(asList(pwk1, pwk2));

        // In this case, we only want to match on the keyword field, not the
        // score field, which shouldn't be included in the elemMatch query.

        // As a result, the query in MongoDB should look like:
        // find({ keywords: { $elemMatch: { keyword: "Scott" } } })

        // NOT:
        // find({ keywords: { $elemMatch: { keyword: "Scott", score: 12 } } })
        assertNotNull(getDs().find(PhotoWithKeywords.class)
                             .field("keywords")
                             .hasThisElement(new Keyword("Scott")).get());
        assertNotNull(getDs().find(PhotoWithKeywords.class)
                             .field("keywords").elemMatch(getDs().find(Keyword.class)
                                                                 .filter("keyword", "Scott"))
                             .get());

        assertNull(getDs().find(PhotoWithKeywords.class)
                          .field("keywords").hasThisElement(new Keyword("Randy"))
                          .get());
        assertNull(getDs().find(PhotoWithKeywords.class)
                          .field("keywords").elemMatch(getDs().find(Keyword.class)
                                                              .filter("keyword", "Randy"))
                          .get());
    }

    @Test
    public void testWhereCodeWScopeQuery() {
        getDs().save(new PhotoWithKeywords(new Keyword("california"), new Keyword("nevada"), new Keyword("arizona")));
        //        CodeWScope hasKeyword = new CodeWScope("for (kw in this.keywords) { if(kw.keyword == kwd) return true; } return false;
        // ", new BasicDBObject("kwd","california"));
        final CodeWScope hasKeyword = new CodeWScope("this.keywords != null", new BasicDBObject());
        assertNotNull(getDs().find(PhotoWithKeywords.class).where(hasKeyword).get());
    }

    @Test
    public void testWhereStringQuery() {
        getDs().save(new PhotoWithKeywords(new Keyword("california"), new Keyword("nevada"), new Keyword("arizona")));
        assertNotNull(getDs().find(PhotoWithKeywords.class).where("this.keywords != null").get());
    }

    @Test
    public void testWhereWithInvalidStringQuery() {
        getDs().save(new PhotoWithKeywords());
        final CodeWScope hasKeyword = new CodeWScope("keywords != null", new BasicDBObject());
        try {
            // must fail
            assertNotNull(getDs().find(PhotoWithKeywords.class).where(hasKeyword.getCode()).get());
            fail("Invalid javascript magically isn't invalid anymore?");
        } catch (MongoInternalException e) {
            // fine
        } catch (MongoException e) {
            // fine
        }

    }

    @Test
    public void testQueryUnmappedData() throws Exception {
        getMorphia().map(Class1.class);
        getDs().ensureIndexes(true);

        getDs().getDB().getCollection("user").save(
            new BasicDBObject()
                .append("@class", Class1.class.getName())
                .append("value1", "foo")
                .append("someMap", new BasicDBObject("someKey", "value")));

        Query<Class1> query = getDs().createQuery(Class1.class);
        query.disableValidation().criteria("someMap.someKey").equal("value");
        Class1 retrievedValue = query.get();
        Assert.assertNotNull(retrievedValue);
        Assert.assertEquals("foo", retrievedValue.value1);
    }

    @Entity(value = "user", noClassnameStored = true)
    private static class Class1 {
        @Id
        private ObjectId id;

        private String value1;

    }

    private int[] copy(final int[] array, final int start, final int count) {
        return copyOfRange(array, start, start + count);
    }

    private void turnOffProfilingAndDropProfileCollection() {
        getDb().command(new BasicDBObject("profile", 0));
        DBCollection profileCollection = getDb().getCollection("system.profile");
        profileCollection.drop();
    }

    @Entity
    public static class Photo {
        @Id
        private ObjectId id;
        private List<String> keywords = singletonList("amazing");

        public Photo() {
        }

        Photo(final List<String> keywords) {
            this.keywords = keywords;
        }
    }

    public static class PhotoWithKeywords {
        @Id
        private ObjectId id;
        @Embedded
        private List<Keyword> keywords = new ArrayList<Keyword>();

        PhotoWithKeywords() {
        }

        PhotoWithKeywords(final String... words) {
            keywords = new ArrayList<Keyword>(words.length);
            for (final String word : words) {
                keywords.add(new Keyword(word));
            }
        }

        PhotoWithKeywords(final Keyword... keyword) {
            keywords.addAll(asList(keyword));
        }
    }

    @Embedded(concreteClass = Keyword.class)
    public static class Keyword {
        private String keyword;
        private Integer score;

        protected Keyword() {
        }

        Keyword(final String k) {
            this.keyword = k;
        }

        Keyword(final String k, final Integer score) {
            this.keyword = k;
            this.score = score;
        }

        Keyword(final Integer score) {
            this.score = score;
        }

        @Override
        public int hashCode() {
            int result = keyword != null ? keyword.hashCode() : 0;
            result = 31 * result + (score != null ? score.hashCode() : 0);
            return result;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Keyword)) {
                return false;
            }

            final Keyword keyword1 = (Keyword) o;

            if (keyword != null ? !keyword.equals(keyword1.keyword) : keyword1.keyword != null) {
                return false;
            }
            return score != null ? score.equals(keyword1.score) : keyword1.score == null;

        }

    }

    private static class ContainsPhotoKey {
        @Id
        private ObjectId id;
        private Key<Photo> photo;
    }

    @Entity
    public static class HasIntId {
        @Id
        private int id;

        protected HasIntId() {
        }

        HasIntId(final int id) {
            this.id = id;
        }
    }

    @Entity
    public static class ContainsPic {
        @Id
        private ObjectId id;
        private String name = "test";
        @Reference
        private Pic pic;
        @Reference(lazy = true)
        private Pic lazyPic;
        @Reference(lazy = true)
        private PicWithObjectId lazyObjectIdPic;
        @Indexed
        private int size;

        public ObjectId getId() {
            return id;
        }

        public void setId(final ObjectId id) {
            this.id = id;
        }

        public PicWithObjectId getLazyObjectIdPic() {
            return lazyObjectIdPic;
        }

        public void setLazyObjectIdPic(final PicWithObjectId lazyObjectIdPic) {
            this.lazyObjectIdPic = lazyObjectIdPic;
        }

        public Pic getLazyPic() {
            return lazyPic;
        }

        public void setLazyPic(final Pic lazyPic) {
            this.lazyPic = lazyPic;
        }

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }

        public Pic getPic() {
            return pic;
        }

        public void setPic(final Pic pic) {
            this.pic = pic;
        }

        public int getSize() {
            return size;
        }

        public void setSize(final int size) {
            this.size = size;
        }
    }

    @Entity
    public static class PicWithObjectId {
        @Id
        private ObjectId id;
        private String name;
    }

    @Entity
    public static class Pic {
        @Id
        private ObjectId id;
        private String name;
        private boolean prePersist = false;

        public Pic() {
        }

        Pic(final String name) {
            this.name = name;
        }

        public ObjectId getId() {
            return id;
        }

        public void setId(final ObjectId id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }

        boolean isPrePersist() {
            return prePersist;
        }

        public void setPrePersist(final boolean prePersist) {
            this.prePersist = prePersist;
        }

        @PrePersist
        public void tweak() {
            prePersist = true;
        }
    }

    @Entity(value = "capped_pic", cap = @CappedAt(count = 1000))
    public static class CappedPic extends Pic {
        public CappedPic() {
        }

        CappedPic(final String name) {
            super(name);
        }
    }

    @Entity(noClassnameStored = true)
    public static class ContainsRenamedFields {
        @Id
        private ObjectId id;
        @Property("first_name")
        private String firstName;
        @Property("last_name")
        private String lastName;

        public ContainsRenamedFields() {
        }

        ContainsRenamedFields(final String firstName, final String lastName) {
            this.firstName = firstName;
            this.lastName = lastName;
        }
    }

    @Entity
    private static class KeyValue {
        @Id
        private ObjectId id;
        /**
         * The list of keys for this value.
         */
        @Indexed(unique = true)
        private List<Object> key;
        /**
         * The id of the value document
         */
        @Indexed
        private ObjectId value;
    }

    @Entity
    private static class GenericKeyValue<T> {

        @Id
        private ObjectId id;

        @Indexed(unique = true)
        private List<Object> key;

        @Embedded
        private T value;
    }

    @Entity
    private static class ReferenceKeyValue {
        @Id
        private ReferenceKey id;
        /**
         * The list of keys for this value.
         */
        @Indexed(unique = true)
        @Reference
        private List<Pic> key;
        /**
         * The id of the value document
         */
        @Indexed
        private ObjectId value;
    }

    static class ReferenceKey {
        @Id
        private ObjectId id;
        private String name;

        ReferenceKey() {
        }

        ReferenceKey(final String name) {
            this.name = name;
        }

        @Override
        public int hashCode() {
            int result = id != null ? id.hashCode() : 0;
            result = 31 * result + (name != null ? name.hashCode() : 0);
            return result;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            final ReferenceKey that = (ReferenceKey) o;

            if (id != null ? !id.equals(that.id) : that.id != null) {
                return false;
            }
            if (name != null ? !name.equals(that.name) : that.name != null) {
                return false;
            }

            return true;
        }
    }

    static class IntVector {
        @Id
        private ObjectId id;
        private String name;
        private int[] scalars;

        IntVector() {
        }

        IntVector(final int... scalars) {
            this.scalars = scalars;
        }
    }

    private static class RectangleComparator implements Comparator<Rectangle> {
        @Override
        public int compare(final Rectangle o1, final Rectangle o2) {
            int compare = Double.compare(o1.getWidth(), o2.getWidth());
            return compare != 0 ? compare : Double.compare(o2.getHeight(), o1.getHeight());
        }
    }

    private static class RectangleComparator1 implements Comparator<Rectangle> {
        @Override
        public int compare(final Rectangle o1, final Rectangle o2) {
            int compare = Double.compare(o2.getHeight(), o1.getHeight());
            return compare != 0 ? compare : Double.compare(o2.getWidth(), o1.getWidth());
        }
    }

    private static class RectangleComparator2 implements Comparator<Rectangle> {
        @Override
        public int compare(final Rectangle o1, final Rectangle o2) {
            int compare = Double.compare(o1.getWidth(), o2.getWidth());
            return compare != 0 ? compare : Double.compare(o1.getHeight(), o2.getHeight());
        }
    }

    private static class RectangleComparator3 implements Comparator<Rectangle> {
        @Override
        public int compare(final Rectangle o1, final Rectangle o2) {
            int compare = Double.compare(o1.getWidth(), o2.getWidth());
            return compare != 0 ? compare : Double.compare(o1.getHeight(), o2.getHeight());
        }
    }

    private void compareLists(final List<Rectangle> list, final Query<Rectangle> query1, final Query<Rectangle> query2,
                              final Comparator<Rectangle> comparator) {
        Collections.sort(list, comparator);
        assertEquals(query1.asList(), list);
        assertEquals(query2.asList(), list);
    }

    private String getCommentFromProfileRecord(final DBObject profileRecord) {
        if (profileRecord.containsField("command")) {
            DBObject commandDocument = ((DBObject) profileRecord.get("command"));
            if (commandDocument.containsField("comment")) {
                return (String) commandDocument.get("comment");
            }
        }
        if (profileRecord.containsField("query")) {
            DBObject queryDocument = ((DBObject) profileRecord.get("query"));
            if (queryDocument.containsField("comment")) {
                return (String) queryDocument.get("comment");
            } else if (queryDocument.containsField("$comment")) {
                return (String) queryDocument.get("$comment");
            }
        }
        return null;
    }
}
