package com.commafeed.backend.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.deepl.api.TextResult;
import com.deepl.api.Translator;
import org.apache.commons.codec.digest.DigestUtils;

import com.commafeed.backend.cache.CacheService;
import com.commafeed.backend.dao.FeedEntryDAO;
import com.commafeed.backend.dao.FeedEntryStatusDAO;
import com.commafeed.backend.dao.FeedSubscriptionDAO;
import com.commafeed.backend.feed.FeedEntryKeyword;
import com.commafeed.backend.model.Feed;
import com.commafeed.backend.model.FeedEntry;
import com.commafeed.backend.model.FeedEntryContent;
import com.commafeed.backend.model.FeedEntryStatus;
import com.commafeed.backend.model.FeedSubscription;
import com.commafeed.backend.model.User;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;

@Slf4j
@RequiredArgsConstructor(onConstructor = @__({ @Inject }))
@Singleton
public class FeedEntryService {

	private final FeedSubscriptionDAO feedSubscriptionDAO;
	private final FeedEntryDAO feedEntryDAO;
	private final FeedEntryStatusDAO feedEntryStatusDAO;
	private final FeedEntryContentService feedEntryContentService;
	private final FeedEntryFilteringService feedEntryFilteringService;
	private final CacheService cache;
	private static  final Translator translator = new Translator("aea4d2b0-8175-251e-38ca-ef0ccd4d079a:fx");
	// 1e98d029-0903-65ab-94fb-fb4f669add87:fx
	// 4b8fb33c-1c3d-8d8c-0ef5-0ee15d693cfe:fx

	/**
	 * this is NOT thread-safe
	 */
	public boolean addEntry(Feed feed, FeedEntry entry, List<FeedSubscription> subscriptions) {

		Long existing = feedEntryDAO.findExisting(entry.getGuid(), feed);
		if (existing != null) {
			return false;
		}

		FeedEntryContent content = feedEntryContentService.findOrCreate(entry.getContent(), feed.getLink());
		entry.setGuidHash(DigestUtils.sha1Hex(entry.getGuid()));
		entry.setContent(content);
		entry.setInserted(new Date());
		entry.setFeed(feed);
		feedEntryDAO.saveOrUpdate(entry);

		// if filter does not match the entry, mark it as read
		for (FeedSubscription sub : subscriptions) {
			boolean matches = true;
			try {
				matches = feedEntryFilteringService.filterMatchesEntry(sub.getFilter(), entry);
			} catch (FeedEntryFilteringService.FeedEntryFilterException e) {
				log.error("could not evaluate filter {}", sub.getFilter(), e);
			}
			if (!matches) {
				FeedEntryStatus status = new FeedEntryStatus(sub.getUser(), sub, entry);
				status.setRead(true);
				feedEntryStatusDAO.saveOrUpdate(status);
			}
		}

		return true;
	}

	public String translateEntry(User user, Long entryId) {
		FeedEntry entry = feedEntryDAO.findById(entryId);
		if(entry == null) {
			return "";
		}

		FeedSubscription sub = feedSubscriptionDAO.findByFeed(user, entry.getFeed());
		if(sub == null) {
			return "";
		}

		FeedEntryContent content = entry.getContent();
		if(content.getIf_translate()) {
			return content.getCh_content();
		}
		String ch_content = XMLTranslate(content.getContent());
		content.setCh_content(ch_content);
		content.setIf_translate(Boolean.TRUE);
		entry.setContent(content);
		feedEntryDAO.update(entry);
		return ch_content;
	}

	private  String XMLTranslate(String str) {
		try {
			// 使用Jsoup解析HTML字符串
			Document document = Jsoup.parse(str, "", Parser.xmlParser());

			// 获取根节点
			org.jsoup.nodes.Element root = document.root();

			// 遍历所有文本节点
			processTextNodes(root);

			return document.outerHtml();
		} catch (Exception e) {
			e.printStackTrace();
			return str;
		}
	}
	private void processTextNodes(org.jsoup.nodes.Element element) {
		// 遍历当前节点的子节点
		for (org.jsoup.nodes.Element child : element.children()) {
			if (child.tagName().equals("p") || child.tagName().equals("span") || child.tagName().equals("b")
					|| child.tagName().equals("caption") || child.tagName().equals("summary") || child.tagName().equals("div")
					|| child.tagName().equals("description")) {
				// 处理文字节点
				String originalText = child.text();
				String translatedText = translateString(originalText); // 调用翻译方法，将原始文本翻译为目标语言
				child.text(translatedText); // 替换文本节点的内容为翻译后的文本
			} else {
				// 递归处理子节点
				processTextNodes(child);
			}
		}
	}

	public String translateString(String str) {
		try {
			TextResult textResult = translator.translateText(str, "en", "zh");
			return textResult.getText();
		} catch (Exception ex) {
			return str;
		}
	}

	public void markEntry(User user, Long entryId, boolean read) {

		FeedEntry entry = feedEntryDAO.findById(entryId);
		if (entry == null) {
			return;
		}

		FeedSubscription sub = feedSubscriptionDAO.findByFeed(user, entry.getFeed());
		if (sub == null) {
			return;
		}

		FeedEntryStatus status = feedEntryStatusDAO.getStatus(user, sub, entry);
		if (status.isMarkable()) {
			status.setRead(read);
			feedEntryStatusDAO.saveOrUpdate(status);
			cache.invalidateUnreadCount(sub);
			cache.invalidateUserRootCategory(user);
		}
	}

	public void starEntry(User user, Long entryId, Long subscriptionId, boolean starred) {

		FeedSubscription sub = feedSubscriptionDAO.findById(user, subscriptionId);
		if (sub == null) {
			return;
		}

		FeedEntry entry = feedEntryDAO.findById(entryId);
		if (entry == null) {
			return;
		}

		FeedEntryStatus status = feedEntryStatusDAO.getStatus(user, sub, entry);
		status.setStarred(starred);
		feedEntryStatusDAO.saveOrUpdate(status);
	}

	public void markSubscriptionEntries(User user, List<FeedSubscription> subscriptions, Date olderThan, List<FeedEntryKeyword> keywords) {
		List<FeedEntryStatus> statuses = feedEntryStatusDAO.findBySubscriptions(user, subscriptions, true, keywords, null, -1, -1, null,
				false, false, null, null, null);
		markList(statuses, olderThan);
		cache.invalidateUnreadCount(subscriptions.toArray(new FeedSubscription[0]));
		cache.invalidateUserRootCategory(user);
	}

	public void markStarredEntries(User user, Date olderThan) {
		List<FeedEntryStatus> statuses = feedEntryStatusDAO.findStarred(user, null, -1, -1, null, false);
		markList(statuses, olderThan);
	}

	private void markList(List<FeedEntryStatus> statuses, Date olderThan) {
		List<FeedEntryStatus> list = new ArrayList<>();
		for (FeedEntryStatus status : statuses) {
			if (!status.isRead()) {
				Date entryDate = status.getEntry().getUpdated();
				if (olderThan == null || entryDate == null || olderThan.after(entryDate)) {
					status.setRead(true);
					list.add(status);
				}
			}
		}
		feedEntryStatusDAO.saveOrUpdate(list);
	}
}
