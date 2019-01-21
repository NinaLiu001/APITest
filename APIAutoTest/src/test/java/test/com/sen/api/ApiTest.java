package test.com.sen.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.util.EntityUtils;
import org.dom4j.DocumentException;
import org.testng.Assert;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Listeners;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;
import com.sen.api.beans.ApiDataBean;
import com.sen.api.excepions.ErrorRespStatusException;
import com.sen.api.listeners.AutoTestListener;
import com.sen.api.listeners.RetryListener;
import com.sen.api.utils.FileUtil;
import com.sen.api.utils.RandomUtil;
import com.sen.api.utils.ReportUtil;
import com.sen.api.utils.SSLClient;
import com.sen.api.utils.StringUtil;

@Listeners({ AutoTestListener.class, RetryListener.class })
public class ApiTest extends TestBase {
	private static HttpClient client;
	private static HashMap<String, String> bodyParas;
	private HashMap<String, String> urlParams = new HashMap<String, String>();

	private void createTestData() {
		String account_name2 = RandomUtil.getRandomText(2);
		String account_name10 = RandomUtil.getRandomText(10);
		int length = RandomUtil.getRandom(2, 10);
		String account_nameValid = RandomUtil.getRandomText(length);
		String account_number1 = RandomUtil.getRandom(1, true);
		bodyParas = new HashMap<>();
		bodyParas.put("account_name2", account_name2);
		bodyParas.put("account_name10", account_name10);
		bodyParas.put("account_nameValid", account_nameValid);
		bodyParas.put("account_number1", account_number1);
	}

	@Parameters({ "excelPath", "sheetName" })
	@BeforeTest
	public void prepareForTest(String excelPath, String sheetName) throws UnsupportedEncodingException,
			ClientProtocolException, IOException, ErrorRespStatusException, Exception {
		createTestData();
		readData(excelPath, sheetName);

		init("api-config", bodyParas);
		// getApiData(context);//过滤数据，run标记为Y的执行。
		// 发起https请求
		client = new SSLClient();// 返回https请求对象
		client.getParams().setParameter(CoreConnectionPNames.CONNECTION_TIMEOUT, 60000);// 请求超时
		client.getParams().setParameter(CoreConnectionPNames.SO_TIMEOUT, 60000);// 读取超时
	}

	@Test(dataProvider = "apiDatas")
	public void apiTest(ApiDataBean apiDataBean) throws Exception {
		if (apiDataBean.getSleep() > 0) {
			// sleep休眠时间大于0的情况下进行暂停休眠
			ReportUtil.log(String.format("sleep %s seconds", apiDataBean.getSleep()));
			Thread.sleep(apiDataBean.getSleep() * 1000);
		}
		String apiParam = super.buildRequestParam(apiDataBean);
		// 封装请求方法
		HttpUriRequest method = parseHttpRequest(apiDataBean.getUrl(), apiDataBean.getMethod(), apiParam);
		String responseData;
		try {
			// 执行
			HttpResponse response = client.execute(method);
			int responseStatus = response.getStatusLine().getStatusCode();
			if (StringUtil.isNotEmpty(apiDataBean.getStatus())) {
				Assert.assertEquals(responseStatus, apiDataBean.getStatus(), "返回状态码与预期不符合!");
			} else {
				// 非2开头状态码为异常请求，抛异常后会进行重跑
				if (200 > responseStatus || responseStatus >= 300) {
					throw new ErrorRespStatusException("返回状态码异常：" + responseStatus);
				}
			}
			HttpEntity respEntity = response.getEntity();
			Header respContentType = response.getFirstHeader("Content-Type");
			if (respContentType != null && respContentType.getValue().contains("download")
					|| respContentType.getValue().contains("octet-stream")) {
				String conDisposition = response.getFirstHeader("Content-disposition").getValue();
				String fileType = conDisposition.substring(conDisposition.lastIndexOf("."), conDisposition.length());
				String filePath = "download/" + RandomUtil.getRandom(8, false) + fileType;
				InputStream is = response.getEntity().getContent();
				Assert.assertTrue(FileUtil.writeFile(is, filePath), "下载文件失败。");
				// 将下载文件的路径放到{"filePath":"xxxxx"}进行返回
				responseData = "{\"filePath\":\"" + filePath + "\"}";
			} else {
				responseData = EntityUtils.toString(respEntity, "UTF-8");
			}
		} catch (Exception e) {
			throw e;
		} finally {
			method.abort();
		}
		// 输出返回数据log
		ReportUtil.log("resp:" + responseData);
		verifyResult(responseData, apiDataBean.getVerify(), apiDataBean.isContains());
		saveResult(responseData, apiDataBean.getSave());
	}
}
