/*
 * Copyright (c) 2016, Mihai Emil Andronache
 * All rights reserved.
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *  1)Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *  2)Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *  3)Neither the name of charles-rest nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.amihaiemil.charles.github;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import org.slf4j.Logger;

import com.amihaiemil.charles.steps.IndexPage;
import com.amihaiemil.charles.steps.IndexSite;
import com.amihaiemil.charles.steps.PreconditionCheckStep;
import com.amihaiemil.charles.steps.Step;

/**
 * The "brain" of the Github agent. Can understand commands and 
 * figure out the Steps that need to be performed to fulfill the 
 * command.
 * @author Mihai Andronache (amihaiemil@gmail.com)
 * @version $Id$
 * @since 1.0.0
 * 
 */
public class Brain {

    /**
     * All the languages that the chatbot can understang/speaks.
     */
    private List<Language> languages = new LinkedList<Language>();

    /**
     * The action's logger.
     */
    private Logger logger;

    /**
     * Location of the log file.
     */
    private LogsLocation logsLoc;

    /**
     * Constructor.
     */
    public Brain(Logger logger, LogsLocation logsLoc) {
        this(logger, logsLoc, Arrays.asList((Language) new English()));
    }

    /**
     * Constructor which takes the responses and languages.
     * @param resp
     * @param langs
     */
    public Brain(Logger logger, LogsLocation logsLoc, List<Language> langs) {
        this.logger = logger;
        this.logsLoc = logsLoc;
        this.languages = langs;
    }
    
    /**
     * Understand a command.
     * @param com Given command.
     * @return Steps.
     * @throws IOException if something goes worng.
     */
    public Steps understand(Command com) throws IOException {
         String authorLogin = com.authorLogin();
         logger.info("Command author's login: " + authorLogin);
         Step steps;
         CommandCategory category = this.categorizeCommand(com);

         switch (category.type()) {
             case "hello":
                 String hello = String.format(category.language().response("hello.comment"), authorLogin);
                 steps = new SendReply(
                             new TextReply(com, hello),
                             this.logger,
                             new Step.FinalStep(this.logger)
                         );
                 break;
             case "indexsite":
                 steps = this.stepsForIndex(com, category.language(), false);
                 break;
             case "indexpage":
                 steps = this.stepsForIndex(com, category.language(), true);
                 break;
             case "deleteindex":
                 steps = null; //todo finish impelmenting this.
                 break;
             default:
                 logger.info("Unknwon command!");
                 String unknown = String.format(
                     category.language().response("unknown.comment"),
                     authorLogin);
                 steps = new SendReply(
                            new TextReply(com, unknown),
                            this.logger,
                            new Step.FinalStep(this.logger)
                        );
                 break;
         }
         return new Steps(
             steps,
             new SendReply(
                 new TextReply(
                     com,
                     String.format(
                         category.language().response("step.failure.comment"),
                         com.authorLogin(), this.logsLoc.address()
                     )
                 ),
                 this.logger,
                 new Step.FinalStep(
                     this.logger,
                     "[ERROR] Some step didn't execute properly."
                 )
             )
         );
    }

    /**
     * Find out the type and Language of a command.
     * @param com Received Command.
     * @param logger Logger to use.
     * @return CommandCategory, which defaults to unknown command and
     *  first language in the agent's languages list (this.languages)
     * @throws IOException 
     */
    private CommandCategory categorizeCommand(Command com) throws IOException {
        CommandCategory category = new CommandCategory("unknown", languages.get(0));
           for(Language l : languages) {
               category = l.categorize(com);
               if(category.isUnderstood()) {
                   this.logger.info("Command type: " + category.type() + ". Language: " + l.getClass().getSimpleName());
                   break;
               }
           }
           return category;
    }

    /**
     * Build and return the steps tree for an index command
     * @param com Received Command, 
     * @param lang Spoken language.
     * @param singlePage is it an index-page or an index-site command?
     * @return Steps that have to be followed to fulfill an index command.
     * @throws IOException If something goes wrong.
     */
    private Step stepsForIndex(Command com, Language lang, boolean singlePage) throws IOException {
        PreconditionCheckStep repoForkCheck;
        if(!singlePage) {
            repoForkCheck = new RepoForkCheck(
                com.repo().json(), this.logger,
                this.indexSiteStep(com, lang),
                this.denialReplyStep(com, lang, "denied.fork.comment")
            );
        } else {
            repoForkCheck = new RepoForkCheck(
                com.repo().json(), this.logger,
                new PageHostedOnGithubCheck(
                    com, this.logger,
                    this.indexPageStep(com, lang),
                    this.denialReplyStep(com, lang, "denied.badlink.comment")
                ),
                this.denialReplyStep(com, lang, "denied.fork.comment")
            );
        }
        PreconditionCheckStep authorOwnerCheck = new AuthorOwnerCheck(
            com, this.logger,
            repoForkCheck,
            new OrganizationAdminCheck(
                com, this.logger,
                repoForkCheck,
                this.denialReplyStep(com, lang, "denied.commander.comment")
            )
        );
        PreconditionCheckStep repoNameCheck = new RepoNameCheck(
            com.repo().json(), this.logger, authorOwnerCheck,
            new GhPagesBranchCheck(
                com, this.logger, authorOwnerCheck,
                this.denialReplyStep(com, lang, "denied.name.comment")
            )
        );        
        return repoNameCheck;
    }

    /**
     * Builds the reply to send to a command that did not 
     * pass a precondition.
     * @return SendReply step.
     */
    private SendReply denialReplyStep(
        Command com, Language lang, String messagekey
    ) {
        Reply rep = new TextReply(
            com,
            String.format(
                 lang.response(messagekey),
                com.authorLogin()
             )
        );
        return
            new SendReply(
                rep, this.logger,
                new Step.FinalStep(
                    this.logger,
                    "Action finished successfully after command denial."
                )
            );
    }

    /**
     * Builds and returns the IndexPage step.
     * @param com Command
     * @param lang Language
     * @return Step
     * @throws IOException 
     */
    public Step indexPageStep(Command com, Language lang) throws IOException {
        String repoName = com.repo().json().getString("name");
        String indexName = com.authorLogin() + "x" + repoName;
        return new SendReply(
            new TextReply(
                com,
                String.format(
                    lang.response("index.start.comment"),
                    com.authorLogin(),
                    this.logsLoc.address()
                )
            ), this.logger,
            new IndexPage(
                com.json().getString("body"),
                indexName.toLowerCase(),
                this.logger,
                this.indexFollowupStep(com, lang)
            )
        );
    }
    
    /**
     * Builds and returns the IndexSite step.
     * @param com Command
     * @param lang Language
     * @return Step
     * @throws IOException 
     */
    public Step indexSiteStep(Command com, Language lang) throws IOException {
        return new SendReply(
            new TextReply(
                com,
                String.format(
                    lang.response("index.start.comment"),
                    com.authorLogin(),
                    this.logsLoc.address()
                )
            ), this.logger,
            new IndexSite(
                com, logger,
                this.indexFollowupStep(com, lang)
            )
        );
    }
    
    /**
     * Builds and returns the steps that need to be performed
     * after an index command.
     * @param com
     * @param lang
     * @return
     * @throws IOException 
     */
    private Step indexFollowupStep(Command com, Language lang) throws IOException{
        Step followUp =
            new StarRepo(
                com.issue().repo(), this.logger,
                new SendReply(
                    new TextReply(
                        com,
                        String.format(
                            lang.response("index.finished.comment"),
                            com.authorLogin(),
                            com.repo().json().getString("name"),
                            this.logsLoc.address()
                        )
                    ), this.logger,
                    new Step.FinalStep(this.logger)
               )
            );
        return followUp;
    }
}
