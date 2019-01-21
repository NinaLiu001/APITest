package test.com.sen.api;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.message.BasicHeader;
import org.dom4j.DocumentException;
import org.testng.Assert;
import org.testng.ITestContext;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Optional;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONPath;
import com.sen.api.beans.ApiDataBean;
import com.sen.api.beans.BaseBean;
import com.sen.api.configs.ApiConfig;
import com.sen.api.excepions.ErrorRespStatusException;
import com.sen.api.utils.AssertUtil;
import com.sen.api.utils.ExcelUtil;
import com.sen.api.utils.FunctionUtil;
import com.sen.api.utils.ReportUtil;
import com.sen.api.utils.StringUtil;

public class TestBase {
	/**
	 * api请求跟路径
	 */
	private static String rootUrl;

	/**
	 * 跟路径是否以‘/’结尾
	 */
	private static boolean rooUrlEndWithSlash = false;

	/**
	 * 所有公共header，会在发送请求的时候添加到http header上
	 */
	private static Header[] publicHeaders;

	private static ApiConfig apiConfig;

	// 所有api测试用例数据
	protected List<ApiDataBean> dataList = new ArrayList<ApiDataBean>();

	/**
	 * 公共参数数据池（全局可用）
	 */
	private static Map<String, String> saveDatas = new HashMap<String, String>();
	// 对于url 中有参数的，可以存入urlParams,

	/**
	 * 替换符，如果数据中包含“${}”则会被替换成公共参数中存储的数据
	 */
	protected Pattern replaceParamPattern = Pattern.compile("\\$\\{(.*?)\\}");
	// http请求客户端对象
	/**
	 * 截取自定义方法正则表达式：__xxx(ooo)
	 */
	protected Pattern funPattern = Pattern.compile("__(\\w*?)\\((([\\w\\\\\\/:\\.\\$]*,?)*)\\)");// __(\\w*?)\\((((\\w*)|(\\w*,))*)\\)
																									// __(\\w*?)\\(((\\w*,?\\w*)*)\\)

	public void init(@Optional("api-config") String envName, HashMap<String, String> testData)
			throws UnsupportedEncodingException, ClientProtocolException, IOException, ErrorRespStatusException,
			Exception {
		// 测试运行环境的配置文件的完整路径
		String configFilePath = Paths.get(System.getProperty("user.dir"), envName + ".xml").toString();
		// 设置api-config中params的值
		ReportUtil.log("api config path:" + configFilePath);
		apiConfig = new ApiConfig(configFilePath);
		apiConfig.readApiConfig(configFilePath);
		// 获取接口请求地址
		rootUrl = apiConfig.getRootUrl();
		rooUrlEndWithSlash = rootUrl.endsWith("/");// 判断接口请求地址是否以“/”结尾

		// 读取 param，并将值保存到公共数据map
		Map<String, String> params = apiConfig.getParams(testData);
		setSaveDates(params);

		// 读取header，并将值添加到所以公共请求头
		List<Header> headers = new ArrayList<Header>();
		apiConfig.getHeaders().forEach((key, value) -> {
			Header header = new BasicHeader(key, value);
			headers.add(header);
		});
		publicHeaders = headers.toArray(new Header[headers.size()]);
	}

	/*
	 * 调用testNG定义的参数后执行Excel数据读取，excelPath代表的文件的系统路径和名称（在TestBase中自动
	 * 加上项目路径，以形成完整的文件路径），sheetName代表的表名
	 */
	// @Optional代表找不到对应的参数时赋予的默认值
	public void readData(@Optional("") String excelPath, @Optional("") String sheetName) throws DocumentException {
		dataList = readExcelData(ApiDataBean.class, excelPath.split(";"), sheetName.split(";"));
	}

	/**
	 * 过滤数据，run标记为Y的执行。
	 * 
	 * @return
	 * @throws DocumentException
	 */
	@DataProvider(name = "apiDatas")
	public Iterator<Object[]> getApiData(ITestContext context) throws DocumentException {
		List<Object[]> dataProvider = new ArrayList<Object[]>();
		for (ApiDataBean data : dataList) {
			if (data.isRun()) {
				dataProvider.add(new Object[] { data });
			}
		}
		return dataProvider.iterator();
	}

	protected String buildRequestParam(ApiDataBean apiDataBean) {
		// 分析处理预参数 （函数生成的参数）
		String preParam = buildParam(apiDataBean.getPreParam());
		savePreParam(preParam);// 保存预存参数 用于后面接口参数中使用和接口返回验证中
		// 处理参数
		String apiParam = buildParam(apiDataBean.getParam());
		return apiParam;
	}

	protected void setSaveDates(Map<String, String> map) {
		saveDatas.putAll(map);
	}

	/**
	 * 组件预参数（处理__fucn()以及${xxxx}）
	 * 
	 * @param apiDataBean
	 * @return
	 */
	protected String buildParam(String param) {
		// 处理${}
		param = getCommonParam(param);
		// Pattern pattern = Pattern.compile("__(.*?)\\(.*\\)");// 取__开头的函数正则表达式
		// Pattern pattern =
		// Pattern.compile("__(\\w*?)\\((\\w*,)*(\\w*)*\\)");// 取__开头的函数正则表达式
		Matcher m = funPattern.matcher(param);
		while (m.find()) {
			String funcName = m.group(1);
			String args = m.group(2);
			String value;
			// bodyfile属于特殊情况，不进行匹配，在post请求的时候进行处理
			if (FunctionUtil.isFunction(funcName) && !funcName.equals("bodyfile")) {
				// 属于函数助手，调用那个函数助手获取。
				value = FunctionUtil.getValue(funcName, args.split(","));
				// 解析对应的函数失败
				Assert.assertNotNull(value, String.format("解析函数失败：%s。", funcName));
				param = StringUtil.replaceFirst(param, m.group(), value);
			}
		}
		return param;
	}

	protected void savePreParam(String preParam) {
		// 通过';'分隔，将参数加入公共参数map中
		if (StringUtil.isEmpty(preParam)) {
			return;
		}
		String[] preParamArr = preParam.split(";");
		String key, value;
		for (String prepar : preParamArr) {
			if (StringUtil.isEmpty(prepar)) {
				continue;
			}
			key = prepar.split("=")[0];
			value = prepar.split("=")[1];
			ReportUtil.log(String.format("存储%s参数，值为：%s。", key, value));
			saveDatas.put(key, value);
		}
	}

	/**
	 * 取公共参数 并替换参数
	 * 
	 * @param param
	 * @return
	 */
	protected String getCommonParam(String param) {
		if (StringUtil.isEmpty(param)) {
			return "";
		}
		Matcher m = replaceParamPattern.matcher(param);// 取公共参数正则
		while (m.find()) {
			String replaceKey = m.group(1);
			String value;
			// 从公共参数池中获取值
			value = getSaveData(replaceKey);
			// 如果公共参数池中未能找到对应的值，该用例失败。
			Assert.assertNotNull(value, String.format("格式化参数失败，公共参数中找不到%s。", replaceKey));
			param = param.replace(m.group(), value);
		}
		return param;
	}

	/**
	 * 获取公共数据池中的数据
	 * 
	 * @param key
	 *            公共数据的key
	 * @return 对应的value
	 */
	protected String getSaveData(String key) {
		if ("".equals(key) || !saveDatas.containsKey(key)) {
			return null;
		} else {
			return saveDatas.get(key);
		}
	}

	protected void verifyResult(String sourchData, String verifyStr, boolean contains) {
		if (StringUtil.isEmpty(verifyStr)) {
			return;
		}
		String allVerify = getCommonParam(verifyStr);
		ReportUtil.log("验证数据：" + allVerify);
		if (contains) {
			// 验证结果包含
			AssertUtil.contains(sourchData, allVerify);
		} else {
			// 通过';'分隔，通过jsonPath进行一一校验
			Pattern pattern = Pattern.compile("([^;]*)=([^;]*)");
			Matcher m = pattern.matcher(allVerify.trim());
			while (m.find()) {
				String actualValue = getBuildValue(sourchData, m.group(1));
				String exceptValue = getBuildValue(sourchData, m.group(2));
				ReportUtil.log(String.format("验证转换后的值%s=%s", actualValue, exceptValue));
				Assert.assertEquals(actualValue, exceptValue, "验证预期结果失败。");
			}
		}
	}

	/**
	 * 获取格式化后的值
	 * 
	 * @param sourchJson
	 * @param key
	 * @return
	 */
	private String getBuildValue(String sourchJson, String key) {
		key = key.trim();
		Matcher funMatch = funPattern.matcher(key);
		if (key.startsWith("$.")) {// jsonpath
			key = JSONPath.read(sourchJson, key).toString();
		} else if (funMatch.find()) {
			// String args;
			// if (funMatch.group(2).startsWith("$.")) {
			// args = JSONPath.read(sourchJson, funMatch.group(2)).toString();
			// } else {
			// args = funMatch.group(2);
			// }
			String args = funMatch.group(2);
			String[] argArr = args.split(",");
			for (int index = 0; index < argArr.length; index++) {
				String arg = argArr[index];
				if (arg.startsWith("$.")) {
					argArr[index] = JSONPath.read(sourchJson, arg).toString();
				}
			}
			String value = FunctionUtil.getValue(funMatch.group(1), argArr);
			key = StringUtil.replaceFirst(key, funMatch.group(), value);

		}
		return key;
	}

	/**
	 * 提取json串中的值保存至公共池中
	 * 
	 * @param json
	 *            将被提取的json串。
	 * @param allSave
	 *            所有将被保存的数据：xx=$.jsonpath.xx;oo=$.jsonpath.oo，将$.jsonpath.
	 *            xx提取出来的值存放至公共池的xx中，将$.jsonpath.oo提取出来的值存放至公共池的oo中
	 */
	protected void saveResult(String json, String allSave) {
		if (null == json || "".equals(json) || null == allSave || "".equals(allSave)) {
			return;
		}
		allSave = getCommonParam(allSave);
		String[] saves = allSave.split(";");
		String key, value;
		for (String save : saves) {
			// key = save.split("=")[0].trim();
			// value = JsonPath.read(json,
			// save.split("=")[1].trim()).toString();
			// ReportUtil.log(String.format("存储公共参数 %s值为：%s.", key, value));
			// saveDatas.put(key, value);

			Pattern pattern = Pattern.compile("([^;=]*)=([^;]*)");
			Matcher m = pattern.matcher(save.trim());
			while (m.find()) {
				key = getBuildValue(json, m.group(1));
				value = getBuildValue(json, m.group(2));

				ReportUtil.log(String.format("存储公共参数   %s值为：%s.", key, value));
				saveDatas.put(key, value);
			}
		}
	}

	/**
	 * 根据配置读取测试用例
	 * 
	 * @param clz
	 *            需要转换的类
	 * @param excelPath
	 *            所有excel的路径配置
	 * @param sheetName
	 *            本次需要过滤的sheet名
	 * @return 返回数据
	 * @throws DocumentException
	 */
	protected <T extends BaseBean> List<T> readExcelData(Class<T> clz, String[] excelPathArr, String[] sheetNameArr)
			throws DocumentException {
		List<T> allExcelData = new ArrayList<T>();// excel文件數組
		List<T> temArrayList = new ArrayList<T>();
		for (String excelPath : excelPathArr) {
			File file = Paths.get(System.getProperty("user.dir"), excelPath).toFile();
			temArrayList.clear();
			if (sheetNameArr.length == 0 || sheetNameArr[0] == "") {
				temArrayList = ExcelUtil.readExcel(clz, file.getAbsolutePath());
			} else {
				for (String sheetName : sheetNameArr) {
					temArrayList.addAll(ExcelUtil.readExcel(clz, file.getAbsolutePath(), sheetName));
				}
			}
			temArrayList.forEach((bean) -> {
				bean.setExcelName(file.getName());
			});
			allExcelData.addAll(temArrayList); // 将excel数据添加至list
		}
		return allExcelData;
	}

	/**
	 * 封装请求方法
	 * 
	 * @param url
	 *            请求路径
	 * @param method
	 *            请求方法
	 * @param param
	 *            请求参数
	 * @return 请求方法
	 * @throws UnsupportedEncodingException
	 */
	public HttpUriRequest parseHttpRequest(String url, String method, String param)
			throws UnsupportedEncodingException {
		// 处理url
		url = parseUrl(url);
		ReportUtil.log("method:" + method);
		ReportUtil.log("url:" + url);
		ReportUtil.log("param:" + param.replace("\r\n", "").replace("\n", ""));
		if ("post".equalsIgnoreCase(method)) {
			// 封装post方法
			HttpPost postMethod = new HttpPost(url);
			postMethod.setHeaders(publicHeaders);
			HttpEntity entity = new StringEntity(param, "UTF-8");
			postMethod.setEntity(entity);
			return postMethod;
		} else if ("upload".equalsIgnoreCase(method)) {
			HttpPost postMethod = new HttpPost(url);
			@SuppressWarnings("unchecked")
			Map<String, String> paramMap = JSON.parseObject(param, HashMap.class);
			MultipartEntity entity = new MultipartEntity();
			for (String key : paramMap.keySet()) {
				String value = paramMap.get(key);
				Matcher m = funPattern.matcher(value);
				if (m.matches() && m.group(1).equals("bodyfile")) {
					value = m.group(2);
					entity.addPart(key, new FileBody(new File(value)));
				} else {
					entity.addPart(key, new StringBody(paramMap.get(key)));
				}
			}
			postMethod.setEntity(entity);
			return postMethod;
		} else {
			// 封装get方法
			HttpGet getMethod = new HttpGet(url);
			getMethod.setHeaders(publicHeaders);
			return getMethod;
		} // delete put....
	}

	/**
	 * 格式化url,替换路径参数等。
	 * 
	 * @param shortUrl
	 * @return
	 */
	private String parseUrl(String shortUrl) {
		// 替换url中的参数
		shortUrl = getCommonParam(shortUrl);
		if (shortUrl.startsWith("http")) {
			return shortUrl;
		}
		if (rooUrlEndWithSlash == shortUrl.startsWith("/")) {
			if (rooUrlEndWithSlash) {
				shortUrl = shortUrl.replaceFirst("/", "");
			} else {
				shortUrl = "/" + shortUrl;
			}
		}
		return rootUrl + shortUrl;
	}

	private String parseUrl(String shortUrl, HashMap<String, String> urlParas) {
		String url = parseUrl(shortUrl);
		if (urlParas.size() > 0) {
			for (String key : urlParas.keySet())
				url += key + "=" + urlParas.get(key) + "/";
		}
		return url;
	}
}
