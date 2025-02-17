package com.autohome.frostmourne.monitor.service.core.query.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.annotation.Resource;

import com.autohome.frostmourne.core.contract.ProtocolException;
import com.autohome.frostmourne.monitor.contract.DataNameContract;
import com.autohome.frostmourne.monitor.contract.DataSourceContract;
import com.autohome.frostmourne.monitor.contract.ElasticsearchDataResult;
import com.autohome.frostmourne.monitor.contract.MetricContract;
import com.autohome.frostmourne.monitor.contract.StatItem;
import com.autohome.frostmourne.monitor.dao.elasticsearch.ElasticsearchInfo;
import com.autohome.frostmourne.monitor.dao.elasticsearch.ElasticsearchSourceManager;
import com.autohome.frostmourne.monitor.dao.elasticsearch.EsRestClientContainer;
import com.autohome.frostmourne.monitor.service.core.domain.MetricData;
import com.autohome.frostmourne.monitor.service.core.query.IElasticsearchDataQuery;
import com.google.common.base.Splitter;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchScrollRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.QueryStringQueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.elasticsearch.search.aggregations.bucket.histogram.ExtendedBounds;
import org.elasticsearch.search.aggregations.bucket.histogram.Histogram;
import org.elasticsearch.search.aggregations.metrics.avg.Avg;
import org.elasticsearch.search.aggregations.metrics.cardinality.Cardinality;
import org.elasticsearch.search.aggregations.metrics.max.Max;
import org.elasticsearch.search.aggregations.metrics.min.Min;
import org.elasticsearch.search.aggregations.metrics.percentiles.Percentiles;
import org.elasticsearch.search.aggregations.metrics.stats.extended.ExtendedStats;
import org.elasticsearch.search.aggregations.metrics.sum.Sum;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ElasticsearchDataQuery implements IElasticsearchDataQuery {

    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticsearchDataQuery.class);

    private static final TimeValue DEFAULT_TIME_VALUE = new TimeValue(10, TimeUnit.MINUTES);

    @Resource
    private ElasticsearchSourceManager elasticsearchSourceManager;

    EsRestClientContainer findEsRestClientContainer(DataSourceContract dataSourceContract) {
        ElasticsearchInfo elasticsearchInfo = new ElasticsearchInfo(dataSourceContract);
        return elasticsearchSourceManager.findEsRestClientContainer(elasticsearchInfo);
    }

    @Override
    public ElasticsearchDataResult query(DataNameContract dataNameContract, DataSourceContract dataSourceContract,
                                         DateTime start, DateTime end, String esQuery, String scrollId,
                                         String sortOrder, Integer intervalInSeconds) {
        EsRestClientContainer esRestClientContainer = this.findEsRestClientContainer(dataSourceContract);
        DateTime queryEnd = end;
        if (queryEnd.getMillis() > System.currentTimeMillis()) {
            queryEnd = DateTime.now();
        }
        BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery().must(new QueryStringQueryBuilder(esQuery))
                .must(QueryBuilders.rangeQuery(dataNameContract.getTimestampField())
                        .from(start.toDateTimeISO().toString())
                        .to(queryEnd.toDateTimeISO().toString())
                        .includeLower(true)
                        .includeUpper(false)
                        .format("date_optional_time"));

        Map<String, String> dataNameProperties = dataNameContract.getSettings();

        String indexPrefix = dataNameProperties.get("indexPrefix");
        String datePattern = dataNameProperties.get("timePattern");
        String[] indices = esRestClientContainer.buildIndices(start, end, indexPrefix, datePattern);

        SearchResponse searchResponse = null;
        try {
            if (Strings.isNullOrEmpty(scrollId)) {
                SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
                searchSourceBuilder.query(boolQueryBuilder);
                searchSourceBuilder.sort(dataNameContract.getTimestampField(), SortOrder.fromString(sortOrder));
                searchSourceBuilder.size(50);
                searchSourceBuilder.trackTotalHits(true);
                searchSourceBuilder.trackScores(false);
                SearchRequest searchRequest = new SearchRequest(indices);
                searchRequest.source(searchSourceBuilder);
                searchRequest.scroll(DEFAULT_TIME_VALUE);
                searchRequest.indicesOptions(EsRestClientContainer.DEFAULT_INDICE_OPTIONS);

                if (intervalInSeconds != null && intervalInSeconds > 0) {
                    DateHistogramAggregationBuilder dateHistogramAggregationBuilder =
                            AggregationBuilders.dateHistogram("date_hist")
                                    .timeZone(DateTimeZone.getDefault())
                                    .extendedBounds(new ExtendedBounds(start.getMillis(), end.getMillis()))
                                    .field(dataNameContract.getTimestampField())
                                    .format("yyyy-MM-dd'T'HH:mm:ssZ")
                                    .dateHistogramInterval(DateHistogramInterval.seconds(intervalInSeconds));
                    searchSourceBuilder.aggregation(dateHistogramAggregationBuilder);
                }
                searchResponse = esRestClientContainer.fetchHighLevelClient().search(searchRequest, RequestOptions.DEFAULT);
            } else {
                SearchScrollRequest searchScrollRequest = new SearchScrollRequest(scrollId);
                searchScrollRequest.scroll(DEFAULT_TIME_VALUE);
                searchResponse = esRestClientContainer.fetchHighLevelClient().scroll(searchScrollRequest, RequestOptions.DEFAULT);
            }
        } catch (IOException ex) {
            throw new ProtocolException(520, "error when search elasticsearch data", ex);
        }
        List<String> headFieldList = null;
        if (dataNameContract.getSettings() != null && dataNameContract.getSettings().containsKey("headFields")) {
            String headFieldStr = dataNameContract.getSettings().get("headFields");
            headFieldList = Splitter.on(",").splitToList(headFieldStr);
        }
        ElasticsearchDataResult elasticsearchDataResult = parseResult(searchResponse, dataNameContract.getTimestampField(), headFieldList);
        if (Strings.isNullOrEmpty(scrollId) && elasticsearchDataResult.getTotal() == 0) {
            try {
                long total = esRestClientContainer.totalCount(boolQueryBuilder, indices);
                elasticsearchDataResult.setTotal(total);
            } catch (Exception ex) {
                LOGGER.error("error when get count", ex);
            }
        }
        return elasticsearchDataResult;
    }

    @Override
    public List<String> queryMappingFileds(DataNameContract dataNameContract,
                                           DataSourceContract dataSourceContract) {
        EsRestClientContainer esRestClientContainer = this.findEsRestClientContainer(dataSourceContract);

        try {
            String index = dataNameContract.getSettings().get("indexPrefix") + "*";
            return esRestClientContainer.fetchAllMappingFields(index);
        } catch (Exception e) {
            throw new RuntimeException("getMapping error: " + e.getMessage(), e);
        }
    }

    @Override
    public MetricData queryElasticsearchMetricValue(DateTime start, DateTime end, MetricContract metricContract) throws IOException {
        MetricData elasticsearchMetric = new MetricData();
        EsRestClientContainer esRestClientContainer = this.findEsRestClientContainer(metricContract.getDataSourceContract());
        BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery()
                .must(new QueryStringQueryBuilder(metricContract.getQueryString()))
                .must(QueryBuilders.rangeQuery(metricContract.getDataNameContract().getTimestampField())
                        .from(start.toDateTimeISO().toString())
                        .to(end.toDateTimeISO().toString())
                        .includeLower(true)
                        .includeUpper(false)
                        .format("date_optional_time"));
        Map<String, String> dataNameProperties = metricContract.getDataNameContract().getSettings();
        String indexPrefix = dataNameProperties.get("indexPrefix");
        String datePattern = dataNameProperties.get("timePattern");
        String[] indices = esRestClientContainer.buildIndices(start, end, indexPrefix, datePattern);
        Long count = null;
        try {
            count = esRestClientContainer.totalCount(boolQueryBuilder, indices);
        } catch (Exception ex) {
            throw new RuntimeException("error when totalCount", ex);
        }
        if (metricContract.getAggregationType().equalsIgnoreCase("count")) {
            elasticsearchMetric.setMetricValue(count);
        }
        if (count == 0) {
            elasticsearchMetric.setMetricValue(0);
            return elasticsearchMetric;
        }
        SearchRequest searchRequest = new SearchRequest(indices);
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        searchSourceBuilder.trackScores(false);
        searchSourceBuilder.trackTotalHits(true);
        searchSourceBuilder.query(boolQueryBuilder).from(0).size(1)
                .sort(metricContract.getDataNameContract().getTimestampField(), SortOrder.DESC);
        attachAggregation(metricContract, searchSourceBuilder);
        searchRequest.source(searchSourceBuilder);
        SearchResponse searchResponse = null;
        int tryCount = 3;
        while (tryCount > 0) {
            searchResponse = esRestClientContainer.fetchHighLevelClient().search(searchRequest, RequestOptions.DEFAULT);
            int hits = searchResponse.getHits().getHits().length;
            if (hits == 0 && tryCount == 1) {
                LOGGER.error("totalCount {}, but hits length is 0, query: {}, start: {}, end: {}", count, metricContract.getQueryString(), start.toString(), end.toString());
                return elasticsearchMetric;
            }
            if (hits > 0) {
                break;
            }
            tryCount--;
        }
        SearchHit latestDoc = searchResponse.getHits().getAt(0);
        elasticsearchMetric.setLatestDocument(latestDoc.getSourceAsMap());
        if (metricContract.getAggregationType().equalsIgnoreCase("count")) {
            if (searchResponse.getHits().getTotalHits() > 0) {
                elasticsearchMetric.setMetricValue(searchResponse.getHits().getTotalHits());
            }
        } else {
            Double numericValue = findAggregationValue(metricContract, searchResponse);
            elasticsearchMetric.setMetricValue(numericValue);
        }
        return elasticsearchMetric;
    }

    private void attachAggregation(MetricContract metricContract, SearchSourceBuilder searchSourceBuilder) {
        String aggType = metricContract.getAggregationType();
        String aggField = metricContract.getAggregationField();
        if (aggType.equalsIgnoreCase("max")) {
            searchSourceBuilder.aggregation(AggregationBuilders.max("maxNumber").field(aggField));
        } else if (aggType.equalsIgnoreCase("min")) {
            searchSourceBuilder.aggregation(AggregationBuilders.min("minNumber").field(aggField));
        } else if (aggType.equalsIgnoreCase("avg")) {
            searchSourceBuilder.aggregation(AggregationBuilders.avg("avgNumber").field(aggField));
        } else if (aggType.equalsIgnoreCase("sum")) {
            searchSourceBuilder.aggregation(AggregationBuilders.sum("sumNumber").field(aggField));
        } else if (aggType.equalsIgnoreCase("cardinality")) {
            searchSourceBuilder.aggregation(AggregationBuilders.cardinality("cardinality").field(aggField));
        } else if (aggType.equalsIgnoreCase("standard_deviation")) {
            searchSourceBuilder.aggregation(AggregationBuilders.extendedStats("extend").field(aggField));
        } else if (aggType.equalsIgnoreCase("percentiles")) {
            searchSourceBuilder.aggregation(AggregationBuilders.percentiles("percentiles")
                    .percentiles(Double.parseDouble(metricContract.getProperties().get("percent").toString())).field(aggField));
        }
    }

    private Double findAggregationValue(MetricContract metricContract, SearchResponse searchResponse) {
        String aggType = metricContract.getAggregationType();
        if (aggType.equalsIgnoreCase("max")) {
            Max max = searchResponse.getAggregations().get("maxNumber");
            return max.getValue();
        }
        if (aggType.equalsIgnoreCase("min")) {
            Min min = searchResponse.getAggregations().get("minNumber");
            return min.getValue();
        }
        if (aggType.equalsIgnoreCase("avg")) {
            Avg avg = searchResponse.getAggregations().get("avgNumber");
            return avg.getValue();
        }
        if (aggType.equalsIgnoreCase("sum")) {
            Sum sum = searchResponse.getAggregations().get("sumNumber");
            return sum.getValue();
        }
        if (aggType.equalsIgnoreCase("cardinality")) {
            Cardinality cardinality = searchResponse.getAggregations().get("cardinality");
            return (double) cardinality.getValue();
        }
        if (aggType.equalsIgnoreCase("standard_deviation")) {
            ExtendedStats extendedStats = searchResponse.getAggregations().get("extend");
            return extendedStats.getStdDeviation();
        }
        if (aggType.equalsIgnoreCase("percentiles")) {
            Percentiles percentiles = searchResponse.getAggregations().get("percentiles");
            return percentiles.percentile(Double.parseDouble(metricContract.getProperties().get("percent").toString()));
        }

        throw new IllegalArgumentException("unsupported aggregation type: " + aggType);
    }

    private ElasticsearchDataResult parseResult(SearchResponse searchResponse, String timestampField, List<String> headFields) {
        ElasticsearchDataResult dataResult = new ElasticsearchDataResult();
        dataResult.setTimestampField(timestampField);
        dataResult.setScrollId(searchResponse.getScrollId());
        dataResult.setTotal(searchResponse.getHits().getTotalHits());
        List<Map<String, Object>> logs = new ArrayList<>();
        for (SearchHit hit : searchResponse.getHits()) {
            logs.add(hit.getSourceAsMap());
            if (dataResult.getFields() == null) {
                dataResult.setFields(new ArrayList<>(hit.getSourceAsMap().keySet()));
                if (headFields == null || headFields.size() == 0) {
                    List<String> flatFields = findFields(hit.getSourceAsMap(), null);
                    dataResult.setFlatFields(flatFields);
                    if (flatFields.size() > 7) {
                        dataResult.setHeadFields(flatFields.subList(0, 6));
                    } else {
                        dataResult.setHeadFields(flatFields);
                    }
                } else {
                    dataResult.setHeadFields(headFields);
                }

            }
        }
        dataResult.setLogs(logs);
        if (searchResponse.getAggregations() == null) {
            return dataResult;
        }
        Histogram dateHistogram = searchResponse.getAggregations().get("date_hist");
        if (dateHistogram != null) {
            StatItem statItem = new StatItem();
            for (Histogram.Bucket bucket : dateHistogram.getBuckets()) {
                statItem.getKeys().add(bucket.getKeyAsString());
                statItem.getValues().add((double) bucket.getDocCount());
            }
            dataResult.setStatItem(statItem);
        }

        return dataResult;
    }

    private List<String> findFields(Map<String, Object> doc, String parentField) {
        List<String> fields = new ArrayList<>();
        for (Map.Entry<String, Object> entry : doc.entrySet()) {
            String field = null;
            if (Strings.isNullOrEmpty(parentField)) {
                field = entry.getKey();
            } else {
                field = parentField + "." + entry.getKey();
            }
            if (entry.getValue() instanceof Map) {
                fields.addAll(findFields((Map<String, Object>) entry.getValue(), field));
            } else {
                fields.add(field);
            }
        }
        return fields;
    }
}
