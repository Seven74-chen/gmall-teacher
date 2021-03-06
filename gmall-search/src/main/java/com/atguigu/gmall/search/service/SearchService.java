package com.atguigu.gmall.search.service;

import com.atguigu.gmall.pms.entity.BrandEntity;
import com.atguigu.gmall.pms.entity.CategoryEntity;
import com.atguigu.gmall.search.pojo.Goods;
import com.atguigu.gmall.search.pojo.SearchParamVo;
import com.atguigu.gmall.search.pojo.SearchResponseAttrVo;
import com.atguigu.gmall.search.pojo.SearchResponseVo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.search.join.ScoreMode;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.Operator;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.Aggregation;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.bucket.nested.ParsedNested;
import org.elasticsearch.search.aggregations.bucket.range.Range;
import org.elasticsearch.search.aggregations.bucket.terms.ParsedLongTerms;
import org.elasticsearch.search.aggregations.bucket.terms.ParsedStringTerms;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SearchService {

    @Autowired
    private RestHighLevelClient restHighLevelClient;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public SearchResponseVo search(SearchParamVo paramVo) {
        try {
            SearchSourceBuilder sourceBuilder = this.buildDSL(paramVo);
            SearchResponse response = this.restHighLevelClient.search(new SearchRequest(new String[]{"goods"}, sourceBuilder), RequestOptions.DEFAULT);
            SearchResponseVo responseVo = this.parseSearchResult(response);
            // ????????????????????????????????????
            responseVo.setPageNum(paramVo.getPageNum());
            responseVo.setPageSize(paramVo.getPageSize());
            return responseVo;
        } catch (IOException e) {
            // ????????????TODO
            e.printStackTrace();
        }
        return null;
    }

    private SearchResponseVo parseSearchResult(SearchResponse response){
        SearchResponseVo responseVo = new SearchResponseVo();

        // ????????????????????????hits
        SearchHits hits = response.getHits();
        // ?????????????????????
        responseVo.setTotal(hits.getTotalHits());

        // ?????????????????????
        SearchHit[] hitsHits = hits.getHits();
        List<Goods> goodsList = Arrays.stream(hitsHits).map(hitsHit -> {
            try {
                // ??????hit??????_source
                String json = hitsHit.getSourceAsString();
                // ????????????????????????Goods??????
                Goods goods = MAPPER.readValue(json, Goods.class);

                // ?????????????????????????????????title
                HighlightField highlightField = hitsHit.getHighlightFields().get("title");
                Text[] fragments = highlightField.getFragments();
                goods.setTitle(fragments[0].string());
                return goods;
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }
            return null;
        }).collect(Collectors.toList());
        responseVo.setGoodsList(goodsList);

        // ????????????????????????????????????
        Map<String, Aggregation> aggregationMap = response.getAggregations().asMap();

        // ??????????????????????????????????????????
        ParsedLongTerms brandIdAgg = (ParsedLongTerms)aggregationMap.get("brandIdAgg");
        List<? extends Terms.Bucket> brandBuckets = brandIdAgg.getBuckets();
        if (!CollectionUtils.isEmpty(brandBuckets)){
            List<BrandEntity> brandEntities = brandBuckets.stream().map(bucket -> {
                BrandEntity brandEntity = new BrandEntity();
                // ???????????????key??????????????????id
                brandEntity.setId(((Terms.Bucket) bucket).getKeyAsNumber().longValue());

                // ??????????????????????????????
                Map<String, Aggregation> brandAggregationMap = ((Terms.Bucket) bucket).getAggregations().asMap();
                // ??????????????????????????????
                ParsedStringTerms brandNameAgg = (ParsedStringTerms)brandAggregationMap.get("brandNameAgg");
                // ????????????????????????????????????
                List<? extends Terms.Bucket> nameBuckets = brandNameAgg.getBuckets();
                if (!CollectionUtils.isEmpty(nameBuckets)) {
                    brandEntity.setName(nameBuckets.get(0).getKeyAsString());
                }

                // ??????logo?????????
                ParsedStringTerms logoAgg = (ParsedStringTerms) brandAggregationMap.get("logoAgg");
                // ??????logo?????????????????????????????????????????????????????????key
                List<? extends Terms.Bucket> logoBuckets = logoAgg.getBuckets();
                if (!CollectionUtils.isEmpty(logoBuckets)){
                    brandEntity.setLogo(logoBuckets.get(0).getKeyAsString());
                }

                return brandEntity;
            }).collect(Collectors.toList());
            responseVo.setBrands(brandEntities);
        }

        // ????????????id?????????
        ParsedLongTerms categoryIdAgg = (ParsedLongTerms)aggregationMap.get("categoryIdAgg");
        List<? extends Terms.Bucket> categoryBuckets = categoryIdAgg.getBuckets();
        if (!CollectionUtils.isEmpty(categoryBuckets)){
            List<CategoryEntity> categoryEntities = categoryBuckets.stream().map(bucket -> {
                CategoryEntity categoryEntity = new CategoryEntity();
                categoryEntity.setId(((Terms.Bucket) bucket).getKeyAsNumber().longValue());

                // ??????????????????????????????
                ParsedStringTerms categoryNameAgg = (ParsedStringTerms)((Terms.Bucket) bucket).getAggregations().get("categoryNameAgg");
                List<? extends Terms.Bucket> nameBuckets = categoryNameAgg.getBuckets();
                if (!CollectionUtils.isEmpty(nameBuckets)){
                    categoryEntity.setName(nameBuckets.get(0).getKeyAsString());
                }
                return categoryEntity;
            }).collect(Collectors.toList());
            responseVo.setCategories(categoryEntities);
        }

        // ??????????????????????????????
        ParsedNested attrAgg = (ParsedNested)aggregationMap.get("attrAgg");
        // ????????????????????????attrIdAgg?????????
        ParsedLongTerms attrIdAgg = (ParsedLongTerms)attrAgg.getAggregations().get("attrIdAgg");
        // ??????????????????????????????
        List<? extends Terms.Bucket> idAggBuckets = attrIdAgg.getBuckets();
        if (!CollectionUtils.isEmpty(idAggBuckets)){
            // ?????????????????????????????????SearchResponseAttrVo
            List<SearchResponseAttrVo> attrVos = idAggBuckets.stream().map(bucket -> {
                SearchResponseAttrVo responseAttrVo = new SearchResponseAttrVo();
                // ??????????????????key??????????????????id
                responseAttrVo.setAttrId(((Terms.Bucket) bucket).getKeyAsNumber().longValue());

                // ????????????????????????
                Map<String, Aggregation> attrAggregationMap = ((Terms.Bucket) bucket).getAggregations().asMap();

                // ?????????????????????????????????
                ParsedStringTerms attrNameAgg = (ParsedStringTerms) attrAggregationMap.get("attrNameAgg");
                List<? extends Terms.Bucket> nameAggBuckets = attrNameAgg.getBuckets();
                if (!CollectionUtils.isEmpty(nameAggBuckets)){
                    responseAttrVo.setAttrName(nameAggBuckets.get(0).getKeyAsString());
                }

                // ?????????????????????????????????
                ParsedStringTerms attrValueAgg = (ParsedStringTerms) attrAggregationMap.get("attrValueAgg");
                List<? extends Terms.Bucket> valueAggBuckets = attrValueAgg.getBuckets();
                if (!CollectionUtils.isEmpty(valueAggBuckets)){
                    List<String> attrValues = valueAggBuckets.stream().map(Terms.Bucket::getKeyAsString).collect(Collectors.toList());
                    responseAttrVo.setAttrValues(attrValues);
                }
                return responseAttrVo;
            }).collect(Collectors.toList());
            responseVo.setFilters(attrVos);
        }

        return responseVo;
    }

    private SearchSourceBuilder buildDSL(SearchParamVo paramVo){
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();

        String keyword = paramVo.getKeyword();
        if (StringUtils.isBlank(keyword)){
            // TODO????????????
            return sourceBuilder;
        }

        // 1.??????????????????
        BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();
        sourceBuilder.query(boolQueryBuilder);

        // 1.1. ????????????
        boolQueryBuilder.must(QueryBuilders.matchQuery("title", keyword).operator(Operator.AND));

        // 1.2. ????????????
        List<Long> brandId = paramVo.getBrandId();
        if (!CollectionUtils.isEmpty(brandId)){
            boolQueryBuilder.filter(QueryBuilders.termsQuery("brandId", brandId));
        }

        // 1.3. ????????????
        List<Long> categoryId = paramVo.getCategoryId();
        if (!CollectionUtils.isEmpty(categoryId)){
            boolQueryBuilder.filter(QueryBuilders.termsQuery("categoryId", categoryId));
        }

        // 1.4. ??????????????????
        // &props=4:6G-8G-12G&props=5:128G
        List<String> props = paramVo.getProps();
        if (!CollectionUtils.isEmpty(props)){
            props.forEach(prop -> {
                // ?????????prop???4:6G-8G-12G ???????????????
                String[] attr = StringUtils.split(prop, ":");
                if (attr != null && attr.length == 2) {
                    BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();
                    // ????????????????????????attrId
                    boolQuery.must(QueryBuilders.termQuery("searchAttrs.attrId", attr[0]));
                    // ???????????????????????????6G-8G-12G?????????-??????
                    String[] attrValues = StringUtils.split(attr[1], "-");
                    boolQuery.must(QueryBuilders.termsQuery("searchAttrs.attrValue", attrValues));
                    boolQueryBuilder.filter(QueryBuilders.nestedQuery("searchAttrs", boolQuery, ScoreMode.None));
                }
            });
        }

        // 1.5. ????????????
        Double priceFrom = paramVo.getPriceFrom();
        Double priceTo = paramVo.getPriceTo();
        // ????????????????????????????????????????????????
        if (priceFrom != null || priceTo != null){
            RangeQueryBuilder rangeQuery = QueryBuilders.rangeQuery("price");
            // ??????priceFrom???????????????gte??????
            if (priceFrom != null){
                rangeQuery.gte(priceFrom);
            }
            // ??????priceTo???????????????lte??????
            if (priceTo != null){
                rangeQuery.lte(priceTo);
            }
            boolQueryBuilder.filter(rangeQuery);
        }

        // 1.6. ????????????
        Boolean store = paramVo.getStore();
        if (store != null) {
            boolQueryBuilder.filter(QueryBuilders.termQuery("store", store));
        }

        // 2.????????????: 0-???????????? 1-???????????? 2-?????????????????? 3-???????????? 4-????????????
        Integer sort = paramVo.getSort();
        if (sort == null){
            sort = 0;
        }
        switch (sort) {
            case 1: sourceBuilder.sort("price", SortOrder.DESC); break;
            case 2: sourceBuilder.sort("price", SortOrder.ASC); break;
            case 3: sourceBuilder.sort("createTime", SortOrder.DESC); break;
            case 4: sourceBuilder.sort("sales", SortOrder.DESC); break;
            default:
                sourceBuilder.sort("_score", SortOrder.DESC);
                break;
        }

        // 3.????????????
        Integer pageNum = paramVo.getPageNum();
        Integer pageSize = paramVo.getPageSize();
        sourceBuilder.from((pageNum - 1) * pageSize);
        sourceBuilder.size(pageSize);

        // 4.??????
        sourceBuilder.highlighter(
                new HighlightBuilder()
                        .field("title")
                        .preTags("<font style='color:red;'>")
                        .postTags("</font>")
        );

        // 5.????????????
        // 5.1. ??????????????????
        sourceBuilder.aggregation(
                AggregationBuilders.terms("brandIdAgg").field("brandId")
                        .subAggregation(AggregationBuilders.terms("brandNameAgg").field("brandName"))
                        .subAggregation(AggregationBuilders.terms("logoAgg").field("logo"))
        );

        // 5.2. ??????????????????
        sourceBuilder.aggregation(
                AggregationBuilders.terms("categoryIdAgg").field("categoryId")
                        .subAggregation(AggregationBuilders.terms("categoryNameAgg").field("categoryName"))
        );

        // 5.3. ????????????????????????
        sourceBuilder.aggregation(
                AggregationBuilders.nested("attrAgg", "searchAttrs")
                        .subAggregation(AggregationBuilders.terms("attrIdAgg").field("searchAttrs.attrId")
                                .subAggregation(AggregationBuilders.terms("attrNameAgg").field("searchAttrs.attrName"))
                                .subAggregation(AggregationBuilders.terms("attrValueAgg").field("searchAttrs.attrValue")))
        );

        // 6.?????????????????????????????????goods???????????????5?????????
        sourceBuilder.fetchSource(new String[]{"skuId", "defaultImage", "price", "title", "subTitle"}, null);

        System.out.println(sourceBuilder.toString());
        return sourceBuilder;
    }
}
