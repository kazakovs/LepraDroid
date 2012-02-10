package com.home.lepradroid.tasks;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import android.text.TextUtils;
import android.util.Pair;

import com.home.lepradroid.LepraDroidApplication;
import com.home.lepradroid.commons.Commons;
import com.home.lepradroid.interfaces.CommentsUpdateListener;
import com.home.lepradroid.interfaces.UpdateListener;
import com.home.lepradroid.listenersworker.ListenersWorker;
import com.home.lepradroid.objects.BaseItem;
import com.home.lepradroid.objects.Comment;
import com.home.lepradroid.serverworker.ServerWorker;
import com.home.lepradroid.utils.FileCache;
import com.home.lepradroid.utils.Logger;

public class GetCommentsTask extends BaseTask
{
    private UUID groupId;
    private UUID id;
    private ArrayList<BaseItem> items = new ArrayList<BaseItem>();
    
    static final Class<?>[] argsClassesOnCommentsUpdate = new Class[1];
    static final Class<?>[] argsClassesOnCommentsUpdateBegin = new Class[1];
    static Method methodOnCommentsUpdate;
    static Method methodOnCommentsUpdateBegin;
    static 
    {
        try
        {
            argsClassesOnCommentsUpdate[0] = UUID.class;
            methodOnCommentsUpdate = CommentsUpdateListener.class.getMethod("OnCommentsUpdate", argsClassesOnCommentsUpdate); 
            
            argsClassesOnCommentsUpdateBegin[0] = UUID.class;
            methodOnCommentsUpdateBegin = CommentsUpdateListener.class.getMethod("OnCommentsUpdateBegin", argsClassesOnCommentsUpdateBegin); 
        }
        catch (Throwable t) 
        {           
            Logger.e(t);
        }        
    }
    
    @Override
    public void finish()
    {
        super.finish();
    }
    
    public GetCommentsTask(UUID groupId, UUID id)
    {
        this.groupId = groupId;
        this.id = id;
    }
    
    @SuppressWarnings("unchecked")
    public void notifyAboutCommentsUpdateBegin()
    {
        final List<CommentsUpdateListener> listeners = ListenersWorker.Instance().getListeners(CommentsUpdateListener.class);
        final Object args[] = new Object[1];
        args[0] = id;
        
        for(CommentsUpdateListener listener : listeners)
        {
            publishProgress(new Pair<UpdateListener, Pair<Method, Object[]>>(listener, new Pair<Method, Object[]> (methodOnCommentsUpdateBegin, args)));
        }
    }
    
    @SuppressWarnings("unchecked")
    public void notifyAboutCommentsUpdate()
    {
        final List<CommentsUpdateListener> listeners = ListenersWorker.Instance().getListeners(CommentsUpdateListener.class);
        final Object args[] = new Object[1];
        args[0] = id;
        
        for(CommentsUpdateListener listener : listeners)
        {
            publishProgress(new Pair<UpdateListener, Pair<Method, Object[]>>(listener, new Pair<Method, Object[]> (methodOnCommentsUpdate, args)));
        }
    }
    
    @Override
    protected Throwable doInBackground(Void... arg0)
    {
        final long startTime = System.nanoTime();
        
        BufferedInputStream stream = null;
        
        try
        {
            ServerWorker.Instance().clearCommentsById(id);
            notifyAboutCommentsUpdateBegin();

            BaseItem post = ServerWorker.Instance().getPostById(groupId, id);
            if(post == null)
                return null; // TODO message
            
            final String pref = "<div id=\"XXXXXXXX\" ";
            final String postTree = "class=\"post tree";
            
            try 
            {
                boolean lastBlock= false;
                String pageA = null, pageB = null;
                final int BUFFER_SIZE = 4 * 1024;
                stream = new BufferedInputStream(ServerWorker.Instance().getContentStream(post.Url), BUFFER_SIZE);
                FileCache ff = new FileCache(LepraDroidApplication.getInstance());
                File file = ff.getFile("delme");
                FileOutputStream fos = new FileOutputStream(file);
                byte[] chars = new byte[BUFFER_SIZE];
                int len = 0;
                while (len != -1)
                {
                    fos.write(chars, 0, len);
                    len = stream.read(chars, 0, BUFFER_SIZE);
                }
                
                FileInputStream in = new FileInputStream(file);

                while(!lastBlock)
                {
                    Arrays.fill(chars, (byte) 0);
                    
                    if((len = in.read(chars, 0, BUFFER_SIZE))<0)
                        lastBlock = true;
                    
                    if(len == 0)
                        continue;
                    else if(pageA == null)
                    {
                        pageA = new String(chars);
                        if(pageB == null) continue;
                    }
                    else
                        pageB = new String(chars);
               
                    while(true)
                    {
                        String html = pageA + (pageB != null ? pageB : "");
                        if(isCancelled()) break;
    
                        int start = html.indexOf(postTree, 0);
                        if(start == -1)
                        {
                            if(pageB != null)
                                pageA = pageB;
                            
                            pageB = null;
    
                            break;
                        }
                        start -= pref.length();   
                        
                        html = html.substring(start, html.length());
                        
                        int end = lastBlock ? html.length() : html.indexOf(postTree, start + pref.length() + 1);
                        if(end == -1)
                        {
                            pageA = html;
                            pageB = null;
    
                            break;
                        }
                        end -= pref.length();
                        
                        pageA = html.substring(end, html.length());  
                        pageB = null;
    
                        parseRecord(html.substring(start, end));
                    }
                }
            } 
            finally 
            {
                stream.close();
            }
        }
        catch (Throwable t)
        {
            Logger.e(t);
            setException(t);
        }
        finally
        {
            if(!items.isEmpty())
                ServerWorker.Instance().addNewComments(groupId, id, items);
            
            notifyAboutCommentsUpdate();
            
            Logger.d("GetCommentsTask time:" + Long.toString(System.nanoTime() - startTime));
        }
   
        return e;
    }
    
    private void parseRecord(String html)
    {
        Element content = Jsoup.parse(html);
        Element element = content.getElementsByClass("dt").first();
        
        Comment comment = new Comment();
        comment.Text = element.text();
        comment.Html = element.html();
        
        Elements images = element.getElementsByTag("img");
        if(!images.isEmpty())
        {
            comment.ImageUrl = images.first().attr("src");
            
            for (Element image : images)
            {
                String width = image.attr("width");
                if(!TextUtils.isEmpty(width))
                    comment.Html = comment.Html.replace("width=\"" + width + "\"", "");
                
                String height = image.attr("height");
                if(!TextUtils.isEmpty(height))
                    comment.Html = comment.Html.replace("height=\"" + height + "\"", "");
                
                comment.Html = comment.Html.replace(image.attr("src"), "http://src.sencha.io/305/305/" + image.attr("src"));
            }
        }

        Elements author = content.getElementsByClass("p");
        if(!author.isEmpty())
        {
            Elements a = author.first().getElementsByTag("a");
            comment.Url = Commons.SITE_URL + a.first().attr("href");
            
            comment.Author = a.get(1).text();
            comment.Signature = author.first().text().split("\\|")[0].replace(comment.Author, "<b>" + comment.Author + "</b>");
        }
        
        Elements vote = content.getElementsByClass("vote");
        if(!vote.isEmpty())
        {
            Elements rating = vote.first().getElementsByTag("em");
            comment.Rating = Integer.valueOf(rating.first().text());
        }
        
        comment.PlusVoted = html.contains("class=\"plus voted\"");
        comment.MinusVoted = html.contains("class=\"minus voted\"");
        
        items.add(comment);
                     
        ServerWorker.Instance().addNewComments(groupId, id, items);
        //notifyAboutCommentsUpdate();
        items.clear();  
    }
}
